package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

public final class PipelineFactory {

  public record PipelineSetup(BatchPipeline pipeline, PromptTemplates prompts) {}

  private static final AppLogger log = AppLogger.getLogger(PipelineFactory.class);

  public static PipelineSetup create(AppConfig cfg) throws IOException {
    if (cfg.ollama.pipeline3StepsMode) {
      log.info("Pipeline: three-stage (concept extraction → card generation → refinement)");
      return new PipelineSetup(
          new ThreeStagePagePipeline(),
          new PromptTemplates(
              null,
              PromptTemplate.loadResource("prompt_step1_concepts.txt"),
              PromptTemplate.loadResource("prompt_step2_cards.txt"),
              PromptTemplate.loadResource("prompt_step3_refine.txt")
          )
      );
    }
    log.info("Pipeline: single-stage");
    return new PipelineSetup(
        new PagePipeline(),
        new PromptTemplates(PromptTemplate.loadResource("prompt.txt"), null, null, null)
    );
  }
}