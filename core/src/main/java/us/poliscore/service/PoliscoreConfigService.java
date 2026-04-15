package us.poliscore.service;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.val;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.LegislativeNamespace;

@ApplicationScoped
public class PoliscoreConfigService {

  private static final String DATASET_CONFIG_PATH = "/datasets.json";

  private List<DeploymentConfig> supportedDeployments;

  @PostConstruct
  void init() {
    this.supportedDeployments = loadDeploymentsFromJson();
  }

  public List<DeploymentConfig> getSupportedDeployments() {
    return supportedDeployments;
  }

  private List<DeploymentConfig> loadDeploymentsFromJson() {
    val mapper = new ObjectMapper();

    try (InputStream is = PoliscoreConfigService.class.getResourceAsStream(DATASET_CONFIG_PATH)) {

      if (is == null) {
        throw new IllegalStateException(
            "Required config file not found on classpath: " + DATASET_CONFIG_PATH);
      }

      List<RawDatasetConfig> rawConfigs =
          mapper.readValue(is, new TypeReference<List<RawDatasetConfig>>() {});

      if (rawConfigs.isEmpty()) {
        throw new IllegalStateException("datasets.json contains zero datasets");
      }

      return rawConfigs.stream()
          .map(this::toDeploymentConfig)
          .toList();

    } catch (Exception e) {
      throw new RuntimeException("Failed to load build_datasets.json", e);
    }
  }

  private DeploymentConfig toDeploymentConfig(RawDatasetConfig raw) {
    Objects.requireNonNull(raw.namespace(), "namespace is required");
    Objects.requireNonNull(raw.year(), "year is required");

    LegislativeNamespace namespace =
        LegislativeNamespace.of(raw.namespace());

    float multiplier = raw.multiplier() != null ? raw.multiplier() : 1.0f;
    Boolean build = Boolean.TRUE.equals(raw.build());

    return new DeploymentConfig(namespace, raw.year(), multiplier, build);
  }

  /**
   * Internal DTO matching build_datasets.json exactly
   */
  @RegisterForReflection
  private record RawDatasetConfig(
      String namespace,
      Integer year,
      Float multiplier,
      Boolean build
  ) {}
}
