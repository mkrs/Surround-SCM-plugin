package andritz.sscm.forensics;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.EnvVarsUtils;
import hudson.scm.SCM;
import hudson.scm.SurroundSCM;
import io.jenkins.plugins.forensics.blame.Blamer;
import io.jenkins.plugins.forensics.blame.BlamerFactory;
import io.jenkins.plugins.forensics.util.FilteredLog;
import jenkins.model.Jenkins;

@Extension
public class SurroundSCMBlameFactory extends BlamerFactory {
   static final String INFO_BLAMER_CREATED = "Invoking sscm annotate to obtain SCM blame information for affected files";
   private static final Logger logger = Logger.getLogger(SurroundSCMBlameFactory.class.getName());

   @Override
   public Optional<Blamer> createBlamer(final SCM scm, final Run<?, ?> build,
            final FilePath workspace, final TaskListener listener, final FilteredLog logger) {
      if (scm instanceof SurroundSCM) {
         EnvVars environment = null;
         try {
            environment = build.getEnvironment(listener);
            if (build instanceof AbstractBuild) {
               EnvVarsUtils.overrideAll(environment, ((AbstractBuild) build).getBuildVariables());
            }
            logger.logInfo(INFO_BLAMER_CREATED);
            return Optional.of(new SurroundSCMBlamer((SurroundSCM) scm, build, environment, workspace, listener));

         } catch (Exception e) {
            // skip blames
         }
      }
      logger.logInfo("Skipping blamer since SCM '%s' is not of type SurroundSCM", scm.getType());
      return Optional.empty();
   }

   @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
   public static void onLoaded() {
      List<BlamerFactory> list = Jenkins.get().getExtensionList(BlamerFactory.class);
      if (list == null) {
         logger.severe("Jenkins has no registered BlamerFactory extensions");
         return;
      }
      for (BlamerFactory factory : list) {
         logger.info("Found BlamerFactory: " + factory.getClass().getName());
      }
   }
}