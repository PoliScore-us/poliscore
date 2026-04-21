package us.poliscore.service.openai;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.entrypoint.batch.BatchOpenAIResponseImporter;
import us.poliscore.model.BuildReport;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.OpenAIService.ChatResult;

@ApplicationScoped
public class OpenAIBatchProcessor {

	// Configurable thread count as a static final:
	private static final int THREADS = Integer.getInteger("poliscore.openai.batch.threads", 8);

	// Rate limiter here is a secondary "absolute max". Actual rate limiting is done in OpenAIService.waitForRateLimit
	private static final int MAX_REQUESTS_PER_MINUTE = Integer.getInteger("poliscore.openai.batch.rpm", 100);

	private static final Duration RATE_LIMIT_REQUEUE_DELAY = Duration
			.ofSeconds(Integer.getInteger("poliscore.openai.batch.rateLimitRequeueDelaySeconds", 30));

	private static final int IMPORT_PROGRESS_LOG_INTERVAL = Integer
			.getInteger("poliscore.openai.batch.importProgressLogInterval", 8);

	// If you want to fail-fast on first fatal error:
	private static final boolean FAIL_FAST = Boolean
			.parseBoolean(System.getProperty("poliscore.openai.batch.failFast", "true"));

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Inject
	BatchOpenAIResponseImporter responseImporter;
	@Inject
	OpenAIService openAIChatService;

	/**
	 * Multi-threaded batch processing that writes a JSONL file in "batch envelope"
	 * format.
	 */
	@SneakyThrows
	public List<File> processBatchImmediately(BuildReport report, List<InterpretationRequest> requests) {
		Log.infof("Performing %d requests to OpenAI (threads=%d).", requests.size(), THREADS);

		var buildTemp = PoliscoreUtil.cacheFile("build");
		buildTemp.mkdirs();

		File outputFile = new File(buildTemp, "openapi-bills.out.jsonl");
		Log.info("Writing responses to file: " + outputFile.getAbsolutePath());

		// Shared work queue (2):
		final BlockingQueue<InterpretationRequest> work = new LinkedBlockingQueue<>(requests);

		// Single-writer queue (4):
		final BlockingQueue<String> linesToWrite = new LinkedBlockingQueue<>(Math.max(THREADS * 4, 64));
		final BlockingQueue<String> linesToImport = new LinkedBlockingQueue<>(Math.max(THREADS * 4, 64));

		// Totals (money/tokens) — thread-safe without contention:
		final LongAdder promptTokens = new LongAdder();
		final LongAdder completionTokens = new LongAdder();
		final LongAdder flexRequests = new LongAdder();
		final LongAdder normalRequests = new LongAdder();
		final LongAdder totalTokens = new LongAdder();
		final DoubleAdder totalUsd = new DoubleAdder();

		// Progress / control:
		final AtomicInteger writtenRequests = new AtomicInteger(0);
		final AtomicInteger handledImports = new AtomicInteger(0);
		final AtomicInteger successfulImports = new AtomicInteger(0);
		final AtomicReference<Throwable> fatal = new AtomicReference<>(null);
		final AtomicBoolean stop = new AtomicBoolean(false);

		// Global rate gate: spaces request *starts* across all threads.
		final GlobalRateGate rateGate = new GlobalRateGate(MAX_REQUESTS_PER_MINUTE);

		// Writer thread: the only thread touching the file.
		final Thread writerThread = new Thread(() -> {
			try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				while (true) {
					String line = linesToWrite.take();
					if (GlobalWriterSignals.POISON_PILL.equals(line))
						break;

					writer.write(line);
					writer.newLine();

					// We flush every single line because we can't afford to waste a request if they ctrl+c. Don't care if its inefficient.
					writer.flush();
					
					writtenRequests.incrementAndGet();
				}
				writer.flush();
			} catch (Throwable t) {
				// If writer dies, that’s fatal.
				fatal.compareAndSet(null, t);
			}
		}, "openai-batch-jsonl-writer");
		writerThread.start();
		
		responseImporter.beginImport();

			final Thread importerThread = new Thread(() -> {
				try {
					while (true) {
						String line = linesToImport.take();
						if (GlobalWriterSignals.POISON_PILL.equals(line))
							break;

						boolean imported = responseImporter.processLine(report, line);
						int handled = handledImports.incrementAndGet();
						if (imported) {
							successfulImports.incrementAndGet();
						}
						logImportProgress(requests.size(), handled, successfulImports.get());
					}
				} catch (Throwable t) {
					fatal.compareAndSet(null, t);
			}
		}, "openai-batch-importer");
		importerThread.start();

		ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
			Thread t = new Thread(r);
			t.setName("openai-batch-worker-" + t.getId());
			t.setDaemon(false);
			return t;
		});
		
		final Thread shutdownHook = new Thread(() -> {
			  try {
			    Log.warn("SIGINT received (Ctrl+C). Stopping OpenAI batch gracefully...");

			    stop.set(true);

			    // Stop workers quickly
			    try { pool.shutdownNow(); } catch (Throwable ignored) {}
			    
			    // Give workers time to finish enqueueing any last lines
			    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

			    // Tell writer to stop (don’t block forever here)
			    try { linesToWrite.offer(GlobalWriterSignals.POISON_PILL, 250, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
			    try { linesToImport.offer(GlobalWriterSignals.POISON_PILL, 250, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}

			    // Give writer/importer a moment to flush/close
			    try { writerThread.join(3_000); } catch (Throwable ignored) {}
			    try { importerThread.join(3_000); } catch (Throwable ignored) {}
			  } catch (Throwable t) {
			    // Never throw from shutdown hooks
			    Log.error("Error in shutdown hook.", t);
			  }
			}, "openai-batch-shutdown-hook");

			Runtime.getRuntime().addShutdownHook(shutdownHook);


		try {
			CountDownLatch workersDone = new CountDownLatch(THREADS);

			for (int i = 0; i < THREADS; i++) {
				pool.submit(() -> {
					try {
						while (!stop.get()) {
							try {
								InterpretationRequest req = work.poll(250, TimeUnit.MILLISECONDS);
								if (req == null) {
									break; // queue drained
								}

								if (FAIL_FAST && fatal.get() != null)
									break;

								// 1) global pacing (prevents “hammering” even with many threads)
								rateGate.acquire();

								try {
									// Do the request (with retries/backoff on 429/5xx etc.)
									ChatResult chat = openAIChatService.chat(req);

									// Track tokens + cost
									var usage = chat.usage();
									if (usage != null) {
									  promptTokens.add(usage.promptTokens());
									  completionTokens.add(usage.completionTokens());
									  totalTokens.add(usage.totalTokens());
									  
									  if ("flex".equals(usage.actualServiceTier()))
										  flexRequests.add(1);
									  else {
										  normalRequests.add(1);
										  Log.warn("Expected flex, but request actually ran as " + usage.actualServiceTier());
									  }
									}
									totalUsd.add(chat.costUsd());

										// Build your existing JSONL “batch envelope” line
										String line = toBatchEnvelopeLine(req, chat);
										linesToWrite.put(line);
										linesToImport.put(line);

									} catch (Throwable t) {
									if (openAIChatService.isRateLimitFailure(t)) {
										Log.warn("OpenAI rate limit persisted after retries for "
												+ req.getData().getOid()
												+ ". Requeueing request instead of halting the batch.", t);
										work.offer(req);
										LockSupport.parkNanos(RATE_LIMIT_REQUEUE_DELAY.toNanos());
										continue;
									}

									Log.error("Fatal error in OpenAI worker. Halting batch.", t);
									fatal.compareAndSet(null, t);
									report.fatal(t);

									if (FAIL_FAST) {
										stop.set(true);
										// Drain remaining work so workers exit faster
										work.clear();
										break;
									}
								}
							} catch (InterruptedException ie) {
								Thread.currentThread().interrupt(); // VERY IMPORTANT
								break; // exit worker
							}
						}
					} finally {
						workersDone.countDown();
					}
				});
			}

			// Wait for workers:
			workersDone.await();

		} finally {
			try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM already shutting down */ }
			
			pool.shutdownNow();

			// Stop writer:
			linesToWrite.put(GlobalWriterSignals.POISON_PILL);
			writerThread.join();
			
			linesToImport.put(GlobalWriterSignals.POISON_PILL);
			importerThread.join();
		}

		// Set totals on BuildReport
		report.setTotalProcessingCost(totalUsd.sum());
		report.setFlexRequests((int) flexRequests.sum());
		report.setFlexRequests((int) normalRequests.sum());

			responseImporter.finishImport(report);
			logImportProgress(requests.size(), handledImports.get(), successfulImports.get());

		return List.of();
	}

	private void logImportProgress(int totalRequests, int handledImports, int successfulImports) {
		if (totalRequests <= 0) {
			return;
		}

		if (handledImports != totalRequests
				&& handledImports % Math.max(1, IMPORT_PROGRESS_LOG_INTERVAL) != 0) {
			return;
		}

		int remaining = Math.max(0, totalRequests - handledImports);
		int failed = Math.max(0, handledImports - successfulImports);
		Log.infof("Imported %d/%d responses (%d failed, %d remaining).",
				successfulImports, totalRequests, failed, remaining);
	}

	private String toBatchEnvelopeLine(InterpretationRequest request, ChatResult chat) {
		val model = Objects.requireNonNullElse(request.getRequestedModel(), OpenAIModel.DEFAULT_MODEL);

		ObjectNode responseNode = MAPPER.createObjectNode();
		responseNode.put("id", "chatcmpl-" + UUID.randomUUID());
		responseNode.put("object", "chat.completion");
		responseNode.put("created", System.currentTimeMillis() / 1000);
		responseNode.put("model", model.getId());

		val choicesArray = MAPPER.createArrayNode();
		val choice = MAPPER.createObjectNode();
		choice.put("index", 0);

		val message = MAPPER.createObjectNode();
		message.put("role", "assistant");
		message.put("content", chat.content());
		choice.set("message", message);
		choice.put("finish_reason", "stop");
		choicesArray.add(choice);
		responseNode.set("choices", choicesArray);

		val usageNode = MAPPER.createObjectNode();
		usageNode.put("prompt_tokens", chat.usage() == null ? 0l : chat.usage().promptTokens());
		usageNode.put("completion_tokens", chat.usage() == null ? 0l : chat.usage().completionTokens());
		usageNode.put("total_tokens", chat.usage() == null ? 0l : chat.usage().totalTokens());
		usageNode.put("service_tier", chat.usage() == null ? "" : chat.usage().actualServiceTier());
		responseNode.set("usage", usageNode);

		val responseEnvelope = MAPPER.createObjectNode();
		responseEnvelope.put("status_code", 200);
		responseEnvelope.set("body", responseNode);

		val line = MAPPER.createObjectNode();
		line.put("custom_id", BatchOpenAIRequest.customDataToCustomId(request.getData()));
		line.set("response", responseEnvelope);

		return line.toString();
	}

	private static final class GlobalWriterSignals {
		private static final String POISON_PILL = "__POISON__";
	}

	/**
	 * A simple global pacing gate: with RPM=60, guarantees at most 1 request-start
	 * per second across all threads. This is the easiest “don’t hammer” safety
	 * valve. If your RPM is higher, raise it.
	 */
	private static final class GlobalRateGate {
		private final long intervalNanos;
		private final AtomicLong next = new AtomicLong(0);

		GlobalRateGate(int rpm) {
			if (rpm <= 0)
				rpm = 60;
			this.intervalNanos = Duration.ofMinutes(1).toNanos() / rpm;
		}

		void acquire() {
			while (true) {
				long now = System.nanoTime();
				long prev = next.get();
				long startAt = Math.max(now, prev);
				long newNext = startAt + intervalNanos;
				if (next.compareAndSet(prev, newNext)) {
					long wait = startAt - now;
					if (wait > 0)
						LockSupport.parkNanos(wait);
					return;
				}
			}
		}
	}
}
