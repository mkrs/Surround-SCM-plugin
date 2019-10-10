package andritz.sscm;

public class SurroundSCMSourceRequest extends SCMSourceRequest {
   private final boolean fetchChangeRequests;

   SurroundSCMSourceRequest(SCMSource source, SurroundSCMSourceContext context, TaskListener listener) {
       super(source, context, listener);
       // copy the relevant details from the context into the request
       this.fetchChangeRequests = context.needsChangeRequests();
   }

   public boolean isFetchChangeRequests() {
       return fetchChangeRequests;
   }
}