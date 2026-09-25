# Programmatic interceptor bindings on OpenWebBeans

I first hit this while following up on [SmallRye Fault Tolerance #711](https://github.com/smallrye/smallrye-fault-tolerance/issues/711). I kept digging because the first explanation did not quite match what I was seeing, and after removing SmallRye from the reproducer I ended up with two separate OpenWebBeans problems.

I do not think these two things should be reviewed as one bug. They have different causes and different patches.

| Problem | What it is | Status |
|---|---|---|
| A | Programmatically registered transitive interceptor bindings are not resolved consistently | Primary issue. Reproducer and candidate implementation are ready |
| B | SE extension archive filtering can compare different URL forms for the same archive | Separate issue. Reproducer and separate candidate patch are ready |

Problem A is the one behind the missing `@Retry` interception from SmallRye FT #711.

Problem B showed up while I was checking the real SmallRye path. It is useful evidence, but it is not needed to reproduce Problem A and I plan to report it separately.

There is no SmallRye packaging change proposed here.

## Problem A: programmatic interceptor bindings

### Quick reproduction

The standalone reproducer is `cdi-isolation/`. It has no SmallRye dependency and discovery is disabled, so the archive scanning problem cannot affect this test.

```sh
mvn -f cdi-isolation/pom.xml -Pweld test
mvn -f cdi-isolation/pom.xml -Powb test
```

The default profiles use Weld 6.0.4.Final and OpenWebBeans 4.1.1 with CDI 4.1.0 and Interceptors 2.2.0.

The short version is:

| Runtime | Result |
|---|---:|
| Weld 6.0.4.Final | 43/43 portable tests pass |
| OpenWebBeans 4.1.1 | 40 of the 43 portable tests fail |
| OpenWebBeans with Patch 1 | 43/43 portable tests pass |

I also reproduced the same binding failure on OWB 4.0.0, 4.0.1, 4.0.2, 4.0.3 and 4.1.0.

It reproduces against OpenWebBeans main at commit `c53140ddbbccb4d0c8f82f9fdf683c7193ee4587` too.

I am not claiming an introducing release or commit.

### Test matrix

The main matrix uses the same counting interceptor for every case. I wanted to keep the mechanism fixed and vary only the things that matter here.

It crosses three dimensions:

| Dimension | Values |
|---|---|
| Registration | `Class + Annotation...`, `AnnotatedType`, configurator |
| Binding chain | programmatic, programmatic to bytecode, bytecode to programmatic |
| Placement | class, method, inherited class binding with overridden method |

That gives 27 primary cases.

There are another 16 controls for non inherited bindings, binding metadata returned by `BeanManager`, public SPI resolution, `AnnotatedType` removal, `@Nonbinding`, repeated `AnnotatedType` registration, method overrides and an unrelated bean.

So the portable reproducer has 43 tests in total.

There are probably other ways to slice this, but this matrix is the one that made the behavior obvious to me and also caught a hole in my first patch.

### What looks wrong

`BeforeBeanDiscovery` can register interceptor binding metadata programmatically.

OpenWebBeans keeps those registrations in `InterceptorsManager`, while parts of the transitive binding walk also rely on annotations declared directly on the binding type.

That means OWB can know that a type is an interceptor binding but still not see the same effective metadata later when it follows the binding graph.

For the SmallRye case the missing edge is basically:

```text
@Retry
  -> @FaultToleranceBinding
  -> FaultToleranceInterceptor
```

If the programmatic edge is not followed, the interceptor does not match.

The standalone reproducer uses its own annotations and a counting interceptor, so SmallRye is not needed to trigger the problem.

The deeper notes are in [`docs/INVESTIGATION.md`](docs/INVESTIGATION.md).

## Candidate implementation for Problem A

[`patches/0001-effective-interceptor-bindings.patch`](patches/0001-effective-interceptor-bindings.patch) is the current candidate implementation against OpenWebBeans main at `c53140ddbbccb4d0c8f82f9fdf683c7193ee4587`.

It touches four production classes:

| Class | Why |
|---|---|
| `InterceptorsManager` | provides the effective registered binding definition |
| `AnnotationManager` | follows effective binding metadata during traversal |
| `BeanManagerImpl` | exposes the same metadata through the public SPI |
| `InterceptorResolutionService` | avoids expanding a discarded class level binding again after a method override |

I am calling this a candidate on purpose. The behavior is covered, but I do not want to assume this is the exact layer where OWB maintainers will want the metadata normalized.

The patch includes 32 focused OWB tests.

With only the new tests added to unmodified OWB production code:

```text
TransitiveInterceptorBindingTest
32 tests
29 failures
```

With Patch 1 applied:

```text
TransitiveInterceptorBindingTest
32 tests
0 failures
```

The existing suites used by the verifier stay green. The configured standalone CDI 4.1 TCK runner also completes its 1,168 selected tests without failures after the patch.

I am only using that as regression evidence. The runner has its existing exclusions, including SE coverage, so this is not a claim of complete CDI certification.

## Real SmallRye control

The root Maven project uses SmallRye Fault Tolerance 7.0.0 and checks a real:

```java
@Retry(maxRetries = 2)
```

The expected invocation count is three.

Under Weld it passes.

Under released OWB, with archive scanning restricted only to isolate the interceptor problem, the method is invoked once. The interceptor binding was not applied.

With the candidate OWB changes the same test reaches three invocations.

The test is not disabled for OpenWebBeans.

## Problem B: SE extension archive filtering

This is a different issue.

With `scanExtensionJars=false`, the extension loader can identify an archive as:

```text
file:/path/library.jar
```

while the scanner deployment map contains:

```text
jar:file:/path/library.jar!/
```

A direct URL comparison does not treat those as the same archive.

The separate candidate is [`patches/0002-extension-archive-filter.patch`](patches/0002-extension-archive-filter.patch). It compares the underlying archive identity and keeps archives that are explicitly enabled with `META-INF/beans.xml`.

Its scanner tests cover file URLs, jar root URLs, exploded directories, paths with spaces, the compatibility option and explicit descriptors.

I am keeping this out of the interceptor binding report. It should be its own OWB issue.

## Full verification

The verifier starts from a clean workspace and uses a fresh Maven repository.

Requirements are JDK 21, Maven 3.9+, Python 3, Git and Maven repository access.

```sh
python3 scripts/verify-local.py /tmp/owb-711-verification
```

To use an existing OpenWebBeans checkout:

```sh
python3 scripts/verify-local.py \
  /tmp/owb-711-verification \
  --owb-source /path/to/openwebbeans
```

The verifier does this in order:

1. checks the unmodified OWB baseline;
2. adds only the new tests and requires the expected failures;
3. applies the candidate patches;
4. reruns the native suites;
5. runs the portable matrix against Weld and released OWB versions;
6. runs the patched OWB snapshot;
7. runs the real SmallRye `@Retry` control.

A Maven failure by itself does not count as a successful reproduction. The script checks the failing suites it expected and rejects missing or skipped reproducer tests.

The recorded run is summarized in [`docs/evidence/verified-2026-09-25-publication/manifest.json`](docs/evidence/verified-2026-09-25-publication/manifest.json).

## Corrections from my first investigation

I originally reported OWB 4.0.3 as a working control. That was wrong.

Fresh runs show the same binding failure on every tested OWB release from 4.0.0 through 4.1.1. So I am not claiming a 4.0.3 to 4.1.0 regression and I am not claiming an introducing commit.

I also first treated the SmallRye deployment failure as a possible packaging problem. Ladicek correctly pointed out that a JAR containing a portable extension and no `beans.xml` is not a bean archive.

No SmallRye `beans.xml` change is proposed.

The later scanner investigation found the separate OWB SE archive filtering problem described as Problem B above.

## References

Relevant specification points are Jakarta Interceptors 2.2 section 3.1.1 and Jakarta CDI 4.1, including `BeforeBeanDiscovery.addInterceptorBinding` and the SE archive rules in section 27.1.

[OWB-1299](https://issues.apache.org/jira/browse/OWB-1299) is useful background for the extension archive scanning option.

## Status

Problem A is ready for upstream review with a SmallRye free reproducer, failing tests and a candidate implementation.

Problem B has its own reproducer and candidate patch and will be reported separately.

Neither candidate has been submitted or accepted by Apache OpenWebBeans yet.

## License

The reproducer is MIT licensed. OpenWebBeans source changes and tests keep the Apache upstream license headers.
