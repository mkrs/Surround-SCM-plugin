package andritz.sscm.forensics;

import java.util.Optional;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.scm.SurroundSCM;
import io.jenkins.plugins.forensics.blame.Blamer;
import io.jenkins.plugins.forensics.blame.BlamerFactory;
import io.jenkins.plugins.forensics.util.FilteredLog;

public class SurroundSCMBlameFactory extends BlamerFactory {
   static final String INFO_BLAMER_CREATED = "Invoking sscm annotate to obtain SCM blame information for affected files";
   static final String ERROR_BLAMER = "Exception while creating a SurroundSCMBlamer instance";

   @Override
   public Optional<Blamer> createBlamer(final SCM scm, final Run<?, ?> build,
            final FilePath workspace, final TaskListener listener, final FilteredLog logger) {
      if (scm instanceof SurroundSCM) {
            return createSurroundSCMBlamer((SurroundSCM) scm, build, workspace, listener, logger);
        }
        logger.logInfo("Skipping blamer since SCM '%s' is not of type SurroundSCM", scm.getType());
        return Optional.empty();
   }

   private Optional<Blamer> createSurroundSCMBlamer(final SurroundSCM sscm, final Run<?, ?> build,
            final FilePath workspace, final TaskListener listener, final FilteredLog logger) {
      //   try {
            // EnvVars environment = build.getEnvironment(listener);
            //GitClient gitClient = git.createClient(listener, environment, build, workspace);
            // String gitCommit = environment.getOrDefault("GIT_COMMIT", "HEAD");

            logger.logInfo(INFO_BLAMER_CREATED);
            return Optional.of(new SurroundSCMBlamer(sscm, build, workspace, listener));
      //   }
      //   catch (IOException | InterruptedException e) {
      //       logger.logException(e, ERROR_BLAMER);

      //       return Optional.empty();
      //   }
    }

}