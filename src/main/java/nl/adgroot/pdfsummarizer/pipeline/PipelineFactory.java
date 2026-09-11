package nl.adgroot.pdfsummarizer.pipeline;

import java.io.IOException;
import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

public final class PipelineFactory {

  public record PipelineSetup(BatchPipeline pipeline, PromptTemplates prompts) {}

  private static final AppLogger log = AppLogger.getLogger(PipelineFactory.class);

  public static PipelineSetup create(AppConfig cfg) throws IOException {
    if (cfg.ollama.pipeline3StepsMode) {
      PromptTemplate step3 = cfg.ollama.refineStep
          ? PromptTemplate.loadResource("prompt_step3_refine.txt")
          : null;
      if (step3 == null) {
        log.info("Pipeline: two-stage (concept extraction → card generation; step 3 disabled)");
      } else {
        log.info("Pipeline: three-stage (concept extraction → card generation → refinement)");
      }
      return new PipelineSetup(
          new ThreeStagePagePipeline(),
          new PromptTemplates(
              null,
              PromptTemplate.loadResource("prompt_step1_concepts.txt"),
              PromptTemplate.loadResource("prompt_step2_cards.txt"),
              step3
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