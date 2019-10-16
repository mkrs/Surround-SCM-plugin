package andritz.sscm;

import jenkins.scm.api.SCMHead;

public class SurroundSCMHead extends SCMHead {
   /**
    *
    */
   private static final long serialVersionUID = 1L;
   private final String repository;

   public SurroundSCMHead(String branch, String repository) {
      super(branch);
      this.repository = repository;
   }

   public String getRepository() {
      return repository;
   }

}