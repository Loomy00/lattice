# Publication and licensing

Audit date: 2026-10-13. The initial public publication is a **source repository**. Local application bundles, installer archives, dependency jars, reference screenshots, supplied design instructions, and database binaries are outside that publication.

## First-party license status

No open-source license has been selected for Lattice's original Java code, scripts, documentation, or generated branding. The instruction to make the repository public authorizes publication, but does not select an MIT, Apache, or other reuse grant. No root LICENSE file is supplied until the copyright holder selects those terms. Public visibility alone does not make the project open source. Third-party license files apply only to identified upstream material.

A permissive MIT license is a reasonable future option for independently authored material, provided the copyright holder wants to allow reuse and confirms the material is theirs to license. Apache-2.0 is another option with explicit patent terms. Neither choice replaces dependency notices, grants Microsoft/Apple trademark rights, or resolves bundled-runtime source obligations. Do not label the current first-party project MIT licensed.

## Independent product and references

The product name is Lattice. Microsoft Access is referenced descriptively to identify database formats and compatibility goals. Lattice is not a Microsoft or Apple product and is not endorsed by either company. The lattice icon is implemented by the project's own drawing code; no Microsoft or Apple product logo, font file, or proprietary runtime is needed to build the source.

Microsoft's [brand guidelines](https://www.microsoft.com/en-us/legal/intellectualproperty/trademarks) and [application guidance](https://learn.microsoft.com/en-us/windows/apps/publish/partner-center/trademark-and-copyright-protection) govern permitted references to its marks. Product descriptions must accurately state implemented compatibility limits and avoid claims of official status, certification, or endorsement.

The supplied Microsoft Access 2016 Runtime was used locally for bounded format research. Its installer, extracted executable/DLL/CAB/MSI files, application databases, and copied graphics are excluded. Microsoft's [copyright permissions policy](https://www.microsoft.com/en-us/legal/intellectualproperty/copyright/permissions) makes software redistribution dependent on the applicable license and imposes conditions on screenshots. Redistribution permission for supplied reference assets has not been established by this audit.

## Publication exclusions

Keep these files locally if needed, but do not stage them in the initial public repository:

- `build/`, including old Access for Mac and current Lattice bundles, JARs, ZIPs, and DMGs; `lib/` downloaded jars.
- Database binaries (`.accdb`, `.mdb`, related Access containers), lock files, working copies, and backups, including generated and upstream fixture binaries. Pinned download metadata and test code remain publishable.
- `docs/references/`: supplied screenshots and earlier screenshot evidence have not been cleared for redistribution.
- `docs/apple-design/`: the verbatim user-supplied skill has no verified redistribution license. Public design documentation should link official Apple guidance and describe the project's own decisions instead.
- Raw local verification logs containing absolute account/workspace paths; system metadata such as `.DS_Store`; environment, credential, or private-key files.
- Microsoft reference inputs and extracted contents, including materials kept outside the project under local work/download directories.

The audit's targeted text scan found local account paths in fixture verification logs and no matching credential/private-key pattern in inspected project text. A pattern scan cannot guarantee every secret or private datum is absent; the actual staged file list and diff must be checked before pushing. Exclusion rules are a safeguard, not permission to publish arbitrary database contents later.

## Third-party compliance and binary release boundary

The application depends on Apache-2.0 UCanAccess and Jackcess and BSD-licensed HSQLDB, with adapted POI/HSQLDB code credited by UCanAccess's original NOTICE. The full HSQLDB jar also contains Oracle's BSD-licensed TableSorter. pyOpenVBA's MIT notice is retained for the design-format research reference. See [the attribution inventory](licenses/THIRD-PARTY.md), including pinned provenance and full license texts. Apache's [redistribution terms](https://www.apache.org/licenses/LICENSE-2.0.html) require preserving applicable license/NOTICE material; the [HSQLDB license](https://hsqldb.org/web/hsqlLicense.html) requires retaining its copyrights, conditions, and disclaimer.

Existing local installers include Azul Zulu/OpenJDK 17.0.18+8 and were made before this audit's new notices. They must not be released unchanged. A future binary release must include all notices, verify exact bundled runtime licenses, and establish a complete corresponding-source arrangement under GPLv2 section 3 for the runtime. This has not been completed here. See [the matching Azul license inventory](https://docs.azul.com/core/tpls/january-2026-respin/zulu17_jdk_tpl.html) and detailed runtime notes in THIRD-PARTY.md.

## Audit limits

This audit reduces identified publication risks; it is not a legal opinion or guarantee against claims. It does not establish ownership of every first-party contribution, patent clearance, freedom to operate, or availability of the name Lattice as a trademark. Interoperability research and independent implementation do not by themselves establish permission to redistribute reference materials. Consult qualified counsel for a commercial launch, disputed ownership, or brand-clearance decision.
