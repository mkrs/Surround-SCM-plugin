package hudson.scm;

public class SurroundSCMAnnotation {
   private final int line;
   private final String user;
   private final int version;

   public SurroundSCMAnnotation(final int line, final String user, final int version) {
      this.line = line;
      this.user = user;
      this.version = version;
   }

   public int getVersion() {
      return version;
   }

   public String getUser() {
      return user;
   }

   public int getLine() {
      return line;
   }
}