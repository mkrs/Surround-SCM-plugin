package hudson.scm;

import java.io.Serializable;

public class SurroundSCMUser implements Serializable {
   private static final long serialVersionUID = -7143070883281986249L;
   
   private final String name;
   private final String fullName;
   private final String email;

   public SurroundSCMUser(String name, String fullName, String email) {
      this.name = name;
      this.fullName = fullName;
      this.email = email;
   }

   public String getEmail() {
      return email;
   }

   public String getFullName() {
      return fullName;
   }

   public String getName() {
      return name;
   }
}
