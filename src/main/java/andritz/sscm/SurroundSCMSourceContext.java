package andritz.sscm;

public class SurroundSCMSouceContext extends SCMSourceContext<SurroundSCMSouceContext, SurroundSCMSouceRequest> {

   // store configuration that can be modified by traits
   // for example, there may be different types of SCMHead instances that can be discovered
   // in which case you would define discovery traits for the different types
   // then those discovery traits would decorate this context to turn on the discovery.

   // exmaple: we have a discovery trait that will ignore branches that have been filed as a change request
   // because they will also be discovered as the change request and there is no point discovering
   // them twice
   private boolean needsChangeRequests;

   // can include additional mandatory parameters
   public SurroundSCMSouceContext(SCMSourceCriteria criteria, SCMHeadObserver observer) {
       super(criteria, observer);
   }

   // follow the builder pattern for "getters" and "setters" and use final liberally
   // i.e. getter methods are *just the field name*
   //      setter methods return this for method chaining and are named to be readable;

   public final boolean needsChangeRequests() { return needsChangeRequests; }

   // in some cases your "setters" logic may be inclusive, in this example, once one trait
   // declares that it needs to know the details of all the change requests, we have to get
   // those details, even if the other traits do not need the information. Hence this
   // "setter" uses inclusive OR logic.
   @NonNull
   public final SurroundSCMSouceContext wantChangeRequests() { needsChangeRequests = true; return this; }

   @NonNull
   @Override
   public SurroundSCMSouceRequest newRequest(@NonNull SCMSource source, @CheckForNull TaskListener listener) {
       return new SurroundSCMSouceRequest(source, this, listener);
   }
}