package us.poliscore.bill;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.val;
import us.poliscore.PoliscoreUtil;
import us.poliscore.ai.BatchOpenAIRequest;
import us.poliscore.ai.BatchOpenAIRequest.BatchBillMessage;
import us.poliscore.ai.BatchOpenAIRequest.BatchOpenAIBody;

public class OpenAIBatchJsonlSerializer {

  /**
   * Matches your previous generator behavior.
   * This is a token *budget* per output file (estimated).
   */
  public static final long DEFAULT_MAX_TOKENS_PER_FILE = 30_000_000L;

  /**
   * OpenAI has an input file size cap; keep a conservative default safety limit.
   * If you already know the exact cap you want, override it.
   */
  public static final long DEFAULT_MAX_BYTES_PER_FILE = 150L * 1024L * 1024L; // 150 MiB

  public OpenAIBatchJsonlSerializer() {
  }

  /**
   * Writes one or more JSONL files, chunked by maxTokensPerFile (estimated).
   * Also optionally enforces maxBytesPerFile as a safety guard against OpenAI upload limits.
   *
   * @param requests requests to serialize
   * @param outputDir output directory
   * @param baseFilenamePrefix e.g. "openai-bills.in" -> "openai-bills.in-1.jsonl", "-2.jsonl", ...
   * @param maxTokensPerFile estimated token budget per file (primary split)
   * @param maxBytesPerFile byte budget per file (secondary safety split). Pass <=0 to disable.
   */
  public List<File> writeChunkedJsonlFiles(
      List<InterpretationRequest> requests,
      File outputDir,
      String baseFilenamePrefix,
      long maxTokensPerFile,
      long maxBytesPerFile
  ) throws IOException {

    if (requests == null || requests.isEmpty()) return List.of();

    if (maxTokensPerFile <= 0) {
      throw new IllegalArgumentException("maxTokensPerFile must be > 0");
    }

    outputDir.mkdirs();

    val mapper = PoliscoreUtil.getObjectMapper();
    val files = new ArrayList<File>();

    int fileIndex = 1;

    long currentTokens = 0;
    long currentBytes = 0;

    File currentFile = new File(outputDir, baseFilenamePrefix + "-" + fileIndex + ".jsonl");
    BufferedWriter writer = Files.newBufferedWriter(
        currentFile.toPath(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
    );
    files.add(currentFile);

    try {
      for (InterpretationRequest r : requests) {

        long reqTokens = estimateTokens(r);
        if (reqTokens > maxTokensPerFile) {
          throw new IllegalStateException(
              "Single request exceeds maxTokensPerFile (" + reqTokens + " > " + maxTokensPerFile
              + ") for oid=" + r.getData().getOid() + ". Reduce prompt size or increase token budget."
          );
        }

        // Build BatchOpenAIRequest (OpenAI Batch input line)
        val messages = List.of(
            new BatchBillMessage("system", r.getSystemMsg()),
            new BatchBillMessage("user", r.getUserMsg())
        );

        BatchOpenAIBody body =
            (r.getRequestedModel() != null)
                ? new BatchOpenAIBody(messages, r.getRequestedModel())
                : new BatchOpenAIBody(messages);

        val lineObj = new BatchOpenAIRequest(r.getData(), body);

        final String line;
        try {
          line = mapper.writeValueAsString(lineObj);
        } catch (JsonProcessingException e) {
          throw new RuntimeException("Failed to serialize BatchOpenAIRequest to JSON for oid=" + r.getData().getOid(), e);
        }

        byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
        long bytesToAdd = lineBytes.length + 1; // newline

        // Optional: enforce byte cap; if a single line exceeds cap, fail fast.
        if (maxBytesPerFile > 0 && bytesToAdd > maxBytesPerFile) {
          throw new IllegalStateException(
              "Single JSONL line exceeds maxBytesPerFile (" + bytesToAdd + " > " + maxBytesPerFile
              + ") for oid=" + r.getData().getOid() + ". Reduce prompt size or increase byte budget."
          );
        }

        // Roll file if adding this request would exceed token budget, OR byte budget (if enabled).
        boolean exceedsTokens = (currentTokens + reqTokens) > maxTokensPerFile;
        boolean exceedsBytes = (maxBytesPerFile > 0) && ((currentBytes + bytesToAdd) > maxBytesPerFile);

        if (exceedsTokens || exceedsBytes) {
          writer.flush();
          writer.close();

          fileIndex++;
          currentTokens = 0;
          currentBytes = 0;

          currentFile = new File(outputDir, baseFilenamePrefix + "-" + fileIndex + ".jsonl");
          writer = Files.newBufferedWriter(
              currentFile.toPath(),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING
          );
          files.add(currentFile);
        }

        writer.write(line);
        writer.newLine();

        currentTokens += reqTokens;
        currentBytes += bytesToAdd;
      }

      writer.flush();
      return files;

    } finally {
      try { writer.close(); } catch (Exception ignored) {}
    }
  }

  /**
   * Convenience overload using defaults (token budget + conservative byte guard).
   */
  public List<File> writeChunkedJsonlFiles(
      List<InterpretationRequest> requests,
      File outputDir,
      String baseFilenamePrefix
  ) throws IOException {
    return writeChunkedJsonlFiles(
        requests,
        outputDir,
        baseFilenamePrefix,
        DEFAULT_MAX_TOKENS_PER_FILE,
        DEFAULT_MAX_BYTES_PER_FILE
    );
  }

  /**
   * Matches your original estimator: chars/4.
   * We include system+user, since those both consume context tokens.
   * We also add a small fixed overhead to avoid undercounting.
   */
  public static long estimateTokens(InterpretationRequest r) {
    String sys = r.getSystemMsg() == null ? "" : r.getSystemMsg();
    String usr = r.getUserMsg() == null ? "" : r.getUserMsg();

    long chars = (long) sys.length() + (long) usr.length();

    // rough: 4 chars/token + small overhead for message framing / JSON / roles
    long est = (chars / 4L) + 50L;
    return Math.max(est, 1L);
  }
}
