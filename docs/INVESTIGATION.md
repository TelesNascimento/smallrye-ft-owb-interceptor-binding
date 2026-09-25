# OpenWebBeans investigation notes

Two independent failures are involved. The SmallRye-free project isolates interceptor binding resolution. The root project uses real SmallRye FT and also exercises archive discovery. The patches target Apache OpenWebBeans `c53140ddbbccb4d0c8f82f9fdf683c7193ee4587`; neither changes SmallRye packaging.

## Problem A: programmatic binding metadata is not traversed

`BeforeBeanDiscoveryImpl` forwards registration to `InterceptorsManager`. The [two registration methods](https://github.com/apache/openwebbeans/blob/c53140ddbbccb4d0c8f82f9fdf683c7193ee4587/webbeans-impl/src/main/java/org/apache/webbeans/intercept/InterceptorsManager.java#L307-L315) retain Class-form definitions and AnnotatedTypes in separate registries.

The [transitive walk](https://github.com/apache/openwebbeans/blob/c53140ddbbccb4d0c8f82f9fdf683c7193ee4587/webbeans-impl/src/main/java/org/apache/webbeans/annotation/AnnotationManager.java#L237-L258) reads `ann.annotationType().getDeclaredAnnotations()`. It can recognize a registered binding type, but does not follow its extension-provided meta-annotations. For SmallRye, the missing edge is `Retry -> FaultToleranceBinding`. The interceptor therefore never matches.

The June candidate expanded programmatic bindings only in `InterceptorsManager.resolveInterceptors`. It restored the simple case, but a newly reached binding's bytecode metadata was never expanded. It also left `BeanManager.getInterceptorBindingDefinition` returning an empty definition for programmatically declared types. That earlier candidate was incomplete and is no longer part of this reproducer.

### Candidate implementation

[Patch 1](../patches/0001-effective-interceptor-bindings.patch) changes four production files:

| File | Responsibility |
|---|---|
| `InterceptorsManager` | Supplies the effective definition and the AnnotatedType-aware binding equality operation. |
| `AnnotationManager` | Traverses effective definitions iteratively and uses effective `@Nonbinding` metadata when checking conflicts. |
| `BeanManagerImpl` | Exposes the effective definition and expands public SPI resolution requests. |
| `InterceptorResolutionService` | Matches already-expanded internal bindings without undoing method/constructor overrides. |

Registered AnnotatedType metadata replaces that type's bytecode metadata; Class-form additions remain additive. The traversal expands each annotation type once, but retains all encountered instances so conflicting values are not silently discarded. The same definitions feed discovery, matching and metadata exposed through the SPI.

For repeated AnnotatedType registration, the first definition is retained consistently. The portable tests check both ordered cases against Weld: first with the binding then without it, and the reverse. This is not a guarantee about observer ordering between unrelated extensions.

Internal matching must not re-expand a class binding after its value was overridden at method level. The regression test includes a higher-priority interceptor bound to the discarded value; if that value is restored, the wrong interceptor returns a failure marker. Checking only whether the correct interceptor runs would miss this defect.

Observable corrections include honoring metadata removals, rejecting real value conflicts that were previously ignored, and rejecting non-binding annotation types in binding-definition lookup. The patch is not described as purely additive.

## Problem B: the extension archive filter compares different URL forms

OWB-1299 introduced `org.apache.webbeans.scanExtensionJars`, preserving default `true` for compatibility. With it explicitly set to `false`, the scanner should exclude extension archives without `beans.xml`.

The [scanner filter](https://github.com/apache/openwebbeans/blob/c53140ddbbccb4d0c8f82f9fdf683c7193ee4587/webbeans-impl/src/main/java/org/apache/webbeans/corespi/scanner/AbstractMetaDataDiscovery.java#L144-L153) compares deployment URLs directly with the extension loader's exclusion set. The [extension loader](https://github.com/apache/openwebbeans/blob/c53140ddbbccb4d0c8f82f9fdf683c7193ee4587/webbeans-impl/src/main/java/org/apache/webbeans/portable/events/ExtensionLoader.java#L112-L139) produces a file URL. In the reproduced case:

- deployment map: `jar:file:/.../library.jar!/`
- exclusion set: `file:/.../library.jar`
- matches under the original comparison: zero
- discovered library beans despite the false option: one

Normalizing only the archive identity changed the false-option result to zero, while the true-option control remained one. The native tests turn that observation into assertions. They also expose the inverse problem: the old filter could exclude an archive explicitly enabled by `beans.xml` when its URL did match.

[Patch 2](../patches/0002-extension-archive-filter.patch) uses the existing XBean file conversion for identity comparison and preserves explicit `META-INF/beans.xml` entries for normal discovery-mode handling. The scanner tests cover file/jar-root URLs, exploded directories, spaces, the compatibility option and descriptor presence. The patch does not change the compatibility default.

## What the evidence establishes

The [portable matrix](../README.md#test-matrix) crosses all declared axes: three registration forms, three binding-chain shapes and three placements. It uses a single counting interceptor. Separate controls cover non-inherited bindings, metadata/SPI lookup, removals, Nonbinding, ordered repeated definitions and extra interception. The root test then checks real `@Retry(maxRetries=2)`.

The [published run](evidence/verified-2026-09-25-publication/manifest.json) freezes the source and patch hashes before execution. It checks baseline native suites, new tests failing against unmodified production, patched native suites, Weld 5/6, six released OWB versions and the patched snapshot. Negative steps are accepted only when the reports demonstrate the expected defect. The public logs normalize machine paths and the focused XML omits JVM properties; test results are unchanged.

The fixed real-FT test uses `scanExtensionJars=false` and **`scanBeansXmlOnly=false`**. Discovery stays enabled. No SmallRye jar is repacked, no jar-name exclusion is used, and the OWB-specific test-disable condition has been removed. The broad `scanBeansXmlOnly=true` option appears only in a negative-control run that isolates the released container's retry failure.

The configured CDI 4.1.0 standalone TCK selection passes 1,168 tests, with its original exclusions unchanged. It excludes SE and other categories. The scanner is therefore tested separately; neither this result nor the core suite is a claim of complete CDI/MP FT certification, full repeatable-container behavior or a performance gain.

## Corrections from the original report

I reported OWB 4.0.3 as a working control. Fresh executions contradict that: every tested release from 4.0.0 through 4.1.1 fails. No introducing commit is claimed, and the earlier suggestion that released jars differed from source tags is withdrawn.

Ladicek's [packaging correction](https://github.com/smallrye/smallrye-fault-tolerance/issues/711#issuecomment-4742259212) was right: an archive containing an extension and no `beans.xml` is not a bean archive. His statement about actual OWB scanning was explicitly tentative. The observed scanner failure belongs in OWB, not a SmallRye packaging PR. CDI 4.1 §27.1 extends the Full rules to SE; the historical TomEE WAR result cannot establish correctness of a different standalone scanner.

The remaining external step is upstream review of the two independent candidates. No Apache approval or released fix is implied by these local results.
