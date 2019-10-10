package andritz.sscm;

public class SurroundSCMRevision extends SCMRevision {

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