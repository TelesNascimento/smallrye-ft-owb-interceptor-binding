# Investigation

How `@Retry` failing on OpenWebBeans was traced to a transitive interceptor binding that a portable
extension registers programmatically, with the false trails called out explicitly.

## The question

SmallRye Fault Tolerance applies `@Retry` under Weld but not under OpenWebBeans. `@Retry` is made an
interceptor binding transitively: `FaultToleranceExtension` registers it at `BeforeBeanDiscovery` so
that `@Retry` implies `@FaultToleranceBinding`, the binding `FaultToleranceInterceptor` carries. If
the container does not fold that transitive binding in during interceptor resolution, `@Retry` is
never intercepted.

## Isolating it from SmallRye

`cdi-isolation/` reproduces only the interceptor mechanism: a hand-rolled extension registers two
interceptor bindings, each transitively bound to `@CountingBinding`, one via
`addInterceptorBinding(Class, Annotation...)` and one via `addInterceptorBinding(AnnotatedType)`.
A bean annotated only with the registered binding must be intercepted.

Result (`docs/evidence/cdi-isolation-runs.txt`):

| Container | direct | transitive via Class | transitive via AnnotatedType |
|---|---|---|---|
| Weld 5.1.3.Final | 1 | 1 | 1 |
| OpenWebBeans 4.0.3 | 1 | 1 | 1 |
| OpenWebBeans 4.1.0 | 1 | 0 | 0 |

The direct binding always applies; the two transitive bindings registered programmatically are
dropped on 4.1.0. So it is a regression between 4.0.3 and 4.1.0, in the container, with no SmallRye
on the classpath.

## Where, and the candidate fix

`InterceptorsManager.resolveInterceptors` matches each interceptor's bindings against the requested
bindings directly. The transitive bindings an extension registers programmatically are kept in
`InterceptorsManager`, not in the binding annotation bytecode, so they are never folded into the
requested set. The fix (`docs/evidence/owb-fix.patch`) expands the requested bindings with that
closure (both forms, terminating, de-duplicating, one-directional on the intercepted-bean side)
before matching. It is a reproducer plus a candidate fix site: toggling that change flips the
in-suite tests and the real `@Retry` count, but whether the fold-in belongs at resolution or at
binding registration is for the OpenWebBeans maintainers to decide. With it, the `webbeans-impl`
suite stays green and the Jakarta CDI TCK 4.1.0 passes (1168, 0 failures).

## What is not the cause (false trails, stated plainly)

1. The first report said the fix was in `AnnotationManager.getInterceptorBindingMetaAnnotations`.
   That method only expands meta-annotations read from the binding bytecode; the matching that
   actually drops the programmatic bindings is in `InterceptorsManager.resolveInterceptors`.
2. The first report cited a deploy-time `UnsatisfiedResolutionException`. That was a CDI API 4.0 vs
   4.1 mismatch in the setup (OpenWebBeans 4.1.0 needs CDI API 4.1). Red herring.
3. The first report framed a packaging/bean-archive issue. With the APIs aligned, OpenWebBeans SE
   scans the jar and double-registers the extension's beans (`AmbiguousResolutionException`), but a
   WAR on TomEE does not scan it (`getBeans()` returns 0), the conformant behavior. So this is an
   OpenWebBeans SE classpath behavior, not a compliance defect, and not a SmallRye bug
   (`docs/evidence/deploy-se-vs-war.txt`).
4. No introducing commit is named: reverting the suspected commit did not fix it, and the release
   tags do not reproduce the released-jar behavior (`docs/evidence/regression-not-pinned.txt`).

## Conclusion

The single genuine defect behind #711 is the interceptor one, on the OpenWebBeans side. The deploy
symptom is an OpenWebBeans SE classpath behavior, out of scope, and requires no SmallRye change.
