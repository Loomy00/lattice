# Third-party licenses and notices

Audited 2026-10-13. These licenses cover their respective upstream components; they do not license Lattice's original code, documentation, or branding. See [LEGAL.md](../LEGAL.md) for first-party status and publication exclusions.

## Java dependencies

The three runtime jars are downloaded separately from Maven Central. Exact URLs and SHA-256 digests are in [dependencies.lock](../dependencies.lock). The inspected ordinary jars contain UCanAccess `net/`, Jackcess `io/`, and HSQLDB `org/` classes respectively; no complete Apache POI jar or shaded POI package is shipped. Retained POMs document dependency scopes.

| Component | Version | License and notices | Upstream |
| --- | --- | --- | --- |
| UCanAccess, maintained spannm fork | 5.1.8 | Apache-2.0; [LICENSE](ucanaccess-5.1.8-LICENSE.txt), unmodified [NOTICE](ucanaccess-5.1.8-NOTICE.txt) extracted from the exact jar | [Project](https://github.com/spannm/ucanaccess/tree/ucanaccess-5.1.8) |
| Jackcess, maintained spannm fork | 5.1.7 | Apache-2.0; [LICENSE](Jackcess-LICENSE.txt); source credits include Health Market Science, Inc., James Ahlborn, and Markus Spann | [Project](https://github.com/spannm/jackcess) |
| HyperSQL / HSQLDB | 2.7.4 | BSD-based; [HSQL and Hypersonic notices](HSQLDB-LICENSE.html); additional Oracle BSD notice for the bundled TableSorter class, [retained here](HSQLDB-TableSorter-LICENSE.txt) | [Official license](https://hsqldb.org/web/hsqlLicense.html) |

UCanAccess's NOTICE attributes adapted financial functions to **Apache POI / Apache Software Foundation** (Apache-2.0), and adapted DatabaseMetaData methods to **HSQLDB** (BSD-based license). These adapted portions are present in UCanAccess even though Jackcess's optional POI dependency is excluded. Preserve that NOTICE and the Apache and HSQLDB license texts with binary distributions. OLE compound-object processing remains unsupported; OLE databases open read only. No Jackcess Encrypt runtime is bundled.

The Oracle TableSorter notice was extracted, without changing its license text, from `org/hsqldb/util/TableSorter.java` in the exact [HSQLDB 2.7.4 sources jar](https://repo.maven.apache.org/maven2/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4-sources.jar), SHA-256 `9e65fd82d140edc029604553ba3c92e5d3637c57e1551824b899730e289cbad0`. It applies to this class inside the distributed full HSQLDB jar regardless of whether Lattice calls it.

## Test fixture provenance

Fixture binaries are excluded from the initial public source repository. The fetch script downloads pinned upstream query/advanced-field/encrypted-refusal fixtures using [fixtures/upstream.lock](../fixtures/upstream.lock). Jackcess test data comes from Apache-2.0 source commit `8a4e40894bfb487f46c5db58b1a04b01895d95c1`; Jackcess Encrypt refusal data comes from Apache-2.0 source commit `5101b9147be4b085b938e178b234ffd7f2f7352e`. The latter's [license](Jackcess-Encrypt-Fixture-LICENSE.txt) is retained. Generated practice databases are synthetic test outputs and are also excluded. Do not publish user databases or extracted Microsoft runtime databases as fixtures.

## Format research attribution

`NativeDesignInspector` was independently implemented using format leads from pyOpenVBA's `_designs.py` and `_storage.py`, source commit `906ad9321e6195d915a9c84d25759981c9a5f70f`. No pyOpenVBA runtime is bundled. Its full **MIT** [copyright and permission notice](pyOpenVBA-LICENSE.md), Copyright (c) 2026 William Smith, is retained conservatively with the research attribution. [Pinned source](https://github.com/WilliamSmithEdward/pyOpenVBA/tree/906ad9321e6195d915a9c84d25759981c9a5f70f). Retained license SHA-256: `0e744f14a1625353519f9a104b53b7110c7b9e40d763eb4d74b68756e0e8d110`.

The native query writer calls pinned Jackcess query-format APIs and serializes catalog records through Jackcess. It does not bundle Microsoft implementation code. Interoperability research does not establish rights to distribute the Microsoft reference installer, extracted components, databases, artwork, or source.

## Optional packaged Java runtime

The local package was built using **Azul Zulu 17.64+17-CA / OpenJDK 17.0.18+8-LTS, macOS aarch64**. It includes GPLv2 code with the Classpath Exception and additional component licenses. The exception permits linking independent application modules under their own terms; it does not remove runtime redistribution requirements.

Preserve the packaged runtime's entire `Contents/runtime/Contents/Home/legal/` tree, including GPL, Classpath Exception, module licenses, and copyright notices. The module legal tree, rather than this summary, identifies the contents of the actual trimmed runtime. See the [matching Azul licensing information](https://docs.azul.com/core/tpls/january-2026-respin/zulu17_jdk_tpl.html) and [OpenJDK license](https://github.com/openjdk/jdk/blob/master/LICENSE).

Before distributing an app/ZIP/DMG that includes this runtime, record exact runtime provenance and satisfy GPLv2 section 3 for its complete corresponding source and build material, using an applicable source-distribution or written-offer route. Merely retaining `legal/` or linking a generic OpenJDK homepage is insufficient. Azul's upstream offer must not be assumed to discharge a downstream distributor's obligations. This source-compliance step is not verified for existing local packages, which are excluded from the initial public repository and are not approved for a binary release by this audit.

For a future binary distribution, include this entire `licenses/` directory and root NOTICE/LEGAL.md, plus the runtime's own notices and the verified corresponding-source arrangement. The current packaging script copies `licenses/`; a package made before this audit lacks the newly added TableSorter and pyOpenVBA notices and must be rebuilt before release.
