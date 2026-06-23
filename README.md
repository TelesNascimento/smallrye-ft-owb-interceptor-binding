# Transitive interceptor binding from a CDI extension on OpenWebBeans (#711)

Minimal reproducer for the interceptor problem behind
[smallrye/smallrye-fault-tolerance#711](https://github.com/smallrye/smallrye-fault-tolerance/issues/711):
**OpenWebBeans 4.1.0 does not apply a transitive interceptor binding that a portable extension
registers programmatically.** Weld and OpenWebBeans 4.0.3 apply it.

That is exactly what `@Retry` needs: SmallRye Fault Tolerance's extension makes `@Retry` a transitive
binding of `@FaultToleranceBinding` (the binding the interceptor carries), registered at
`BeforeBeanDiscovery`. When the container does not fold that transitive binding in, `@Retry` is never
intercepted.

## The reproducer to run: `cdi-isolation/`

[`cdi-isolation/`](cdi-isolation) is a standalone, **SmallRye-free** module. A hand-rolled extension
registers two interceptor bindings, each transitively bound to `@CountingBinding`, one via
`addInterceptorBinding(Class, Annotation...)` and one via `addInterceptorBinding(AnnotatedType)`.
A bean annotated only with the registered binding must be intercepted, like a directly bound bean.

```
cd cdi-isolation
mvn clean test -Pweld                                            # PASS (Weld, the CDI RI)
mvn clean test -Powb -Dversion.owb=4.0.3 -Dcdi.api.version=4.0.1  # PASS
mvn clean test -Powb -Dversion.owb=4.1.0 -Dcdi.api.version=4.1.0  # FAIL (the regression)
```

Result (JDK 21, against the Maven Central jars):

| Container | direct `@CountingBinding` | transitive via Class | transitive via AnnotatedType |
|---|---|---|---|
| Weld 5.1.3.Final | applied (1) | applied (1) | applied (1) |
| OpenWebBeans 4.0.3 | applied (1) | applied (1) | applied (1) |
| OpenWebBeans 4.1.0 | applied (1) | **not applied (0)** | **not applied (0)** |

So the direct binding always works; only the transitive bindings an extension registers
programmatically are dropped on 4.1.0. It is a regression between 4.0.3 and 4.1.0.

## Where it is, and the fix

`InterceptorsManager.resolveInterceptors` matches interceptors against the requested bindings
directly and does not fold in the transitive bindings the extension registered (those are kept in
`InterceptorsManager`, not in the binding annotation bytecode). An additive fix that expands the
requested bindings with that programmatically-registered transitive closure (both registration
forms, terminating and de-duplicating) before matching makes the reproducer pass.

- Patch: [`docs/evidence/owb-fix.patch`](docs/evidence/owb-fix.patch).
- It is offered as a reproducer plus a **candidate fix site**, not a confirmed root cause: toggling
  exactly that change flips the reproducer and the real `@Retry` count, but whether the fold-in
  belongs at resolution or at binding registration is for the OpenWebBeans maintainers to decide.
- With the fix, the OpenWebBeans `webbeans-impl` suite stays green and the Jakarta CDI TCK 4.1.0
  passes (1168 tests, 0 failures): no-regression. The TCK does not exercise this case.
- I did not pin the introducing commit: reverting the suspected commit did not fix it, and the
  release tags do not reproduce the released-jar behavior, so I am not naming one.

The fix belongs in OpenWebBeans. This is not a SmallRye bug.

## End-to-end control: real `@Retry` under Weld

The root module ([`src/test`](src/test)) runs a real `@Retry(maxRetries = 2)` under Weld and asserts
3 invocations (1 + 2 retries). With the OpenWebBeans interceptor fix in place, the same `@Retry`
goes from 1 invocation (not applied) to 3 on OpenWebBeans 4.1.0, matching Weld.

```
mvn clean test -Pweld
```

## A separate, non-issue: OpenWebBeans SE bean scanning

While investigating I first reported a deployment failure on OpenWebBeans. That was two things, and
neither is a SmallRye bug:

1. A CDI API 4.0 vs 4.1 mismatch in my own setup (OpenWebBeans 4.1.0 needs CDI API 4.1), which
   surfaced as a misleading `UnsatisfiedResolutionException`. Red herring.
2. With the APIs aligned, OpenWebBeans **SE** scans the `smallrye-fault-tolerance` jar (which ships
   an extension and no `beans.xml`) and registers the extension's beans twice, giving
   `AmbiguousResolutionException`. But a real web deployment is conformant: a WAR on TomEE (which
   uses OpenWebBeans) does **not** scan that jar (`getBeans()` returns 0), exactly as CDI 4.1 says
   (an archive with an extension and no `beans.xml` is not a bean archive). So this is an
   OpenWebBeans SE classpath behavior, not a compliance defect, and SmallRye is correct to register
   its beans via `addAnnotatedType` and ship no `beans.xml`.

Details in [`docs/INVESTIGATION.md`](docs/INVESTIGATION.md).

## Sources

- smallrye/smallrye-fault-tolerance#711
- SmallRye Fault Tolerance: https://github.com/smallrye/smallrye-fault-tolerance
- OpenWebBeans: https://openwebbeans.apache.org/
- Jakarta Interceptors (transitive interceptor bindings); Jakarta CDI 4.1 (bean archives).

## License

MIT. See `LICENSE`.
