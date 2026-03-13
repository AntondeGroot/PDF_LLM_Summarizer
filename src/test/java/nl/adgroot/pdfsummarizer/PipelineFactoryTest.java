package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pipeline.PagePipeline;
import nl.adgroot.pdfsummarizer.pipeline.PipelineFactory;
import nl.adgroot.pdfsummarizer.pipeline.ThreeStagePagePipeline;
import org.junit.jupiter.api.Test;

class PipelineFactoryTest {

  @Test
  void create_singleStageMode_returnsPagePipeline() throws Exception {
    AppConfig cfg = new AppConfig();
    cfg.ollama.pipeline3StepsMode = false;

    PipelineFactory.PipelineSetup setup = PipelineFactory.create(cfg);

    assertInstanceOf(PagePipeline.class, setup.pipeline());
  }

  @Test
  void create_singleStageMode_promptsHasSingleTemplate_andNoStepTemplates() throws Exception {
    AppConfig cfg = new AppConfig();
    cfg.ollama.pipeline3StepsMode = false;

    PipelineFactory.PipelineSetup setup = PipelineFactory.create(cfg);

    assertNotNull(setup.prompts().single(), "single-stage prompt must be loaded");
    assertNull(setup.prompts().step1(), "step1 must be null in single-stage mode");
    assertNull(setup.prompts().step2(), "step2 must be null in single-stage mode");
    assertNull(setup.prompts().step3(), "step3 must be null in single-stage mode");
  }

  @Test
  void create_threeStageMode_returnsThreeStagePagePipeline() throws Exception {
    AppConfig cfg = new AppConfig();
    cfg.ollama.pipeline3StepsMode = true;

    PipelineFactory.PipelineSetup setup = PipelineFactory.create(cfg);

    assertInstanceOf(ThreeStagePagePipeline.class, setup.pipeline());
  }

  @Test
  void create_threeStageMode_promptsHasAllStepTemplates_andNoSingleTemplate() throws Exception {
    AppConfig cfg = new AppConfig();
    cfg.ollama.pipeline3StepsMode = true;

    PipelineFactory.PipelineSetup setup = PipelineFactory.create(cfg);

    assertNull(setup.prompts().single(), "single must be null in three-stage mode");
    assertNotNull(setup.prompts().step1(), "step1 must be loaded");
    assertNotNull(setup.prompts().step2(), "step2 must be loaded");
    assertNotNull(setup.prompts().step3(), "step3 must be loaded");
  }
}