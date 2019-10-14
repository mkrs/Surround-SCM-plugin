package andritz.sscm;

import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;

public class SurroundSCMRevision extends SCMRevision {
   
   /**
    *
    */
   private static final long serialVersionUID = 1L;
    
   protected SurroundSCMRevision(SCMHead head) {
      super(head);
   }

   @Override
   public boolean equals(Object obj) {
      if (obj instanceof SurroundSCMRevision) {
         return ((SurroundSCMRevision)obj).getHead() == getHead();
      }
      return false;
   }

   @Override
   public int hashCode() {
      return getHead().hashCode();
   }
}