# Developer guide

## Repository layout

| Folder | Content |
|----|----|
| `qupath-extension-qust/` | the QuPath extension (Java, Gradle): `src/main/java/qupath/ext/qust` and the smoke test in `tools/` |
| `registration/` | the Fiji plugin (Java, Maven) |
| `python/` | `train.py`, `classification.py` and `requirements.txt` |
| `data/cci/` | the ligand-receptor table |
| `docs/` | this documentation (Sphinx) |
| `build-release.sh` | builds the three release files into `dist/` |

## Building

``` bash
./build-release.sh          # JDK 25, JDK 17+, Maven and zip; writes dist/
```

or each part alone:

``` bash
cd qupath-extension-qust && ./gradlew build      # build/libs/qupath-extension-qust-<version>.jar
cd registration && mvn clean package             # target/ST2WSI_Registration-<version>.jar
```

The extension is compiled against the published QuPath
(`gradle.ext.qupathVersion` in `settings.gradle`, 0.7.0). Another
release can be targeted with `-PqupathVersion=<version>`; the QuPath
version catalog of that release supplies JavaFX, ImageJ, JTS and the
other libraries QuPath provides.

The libraries QuPath does **not** provide are bundled into the jar by
the shadow plugin: OpenCSV, imgscalr, org.json, and the Parquet reader
with a shaded Hadoop client (used for `cells.parquet` of Xenium).
JavaFX, QuPath, ImageJ, JTS, Gson and the logging are `compileOnly`. The
jar is about 70 MB because of Hadoop.

The StarDist extension is not a dependency:
`StarDistCellNucleusDetection` calls it by reflection.

## Documentation

``` bash
pip install -r docs/requirements.txt
cd docs && make html        # _build/html/index.html
```

## Testing

`qupath-extension-qust/tools/smoke.sh` starts a real QuPath (a built
distribution) with the jar under a virtual display (`xvfb-run`) and runs
the commands on a small synthetic slide with two cell types:

``` bash
QUPATH_APP=/path/to/QuPath/lib/app tools/smoke.sh
QUST_PYTHON=/path/to/python tools/smoke.sh        # also trains and applies a model
```

It checks the menus; Simple Tissue Detection; Delaunay Connections (all
links, distance threshold, same class); Normalize Transcripts (sum,
`log(1 + x)`, raw values unchanged); Neighboring Cell Type Composition;
Cell Spatial Profiling by Classification; Cell-Cell Interaction Analysis
(raw and normalized); Cell Spatial Density; Pseudo Spot Generation; both
image exports; and, with `QUST_PYTHON`, `train.py`, Object
Classification, DBSCAN-CellX and Region Segmentation. With
`STARDIST_JAR` and `STARDIST_MODELS` it also runs StarDist Nucleus
Detection (this needs the OpenCV natives of QuPath to load).

Not covered by an automatic test: the Xenium, CosMx and Visium loaders,
and the registration plugin.

The test does not store connections, so it exercises the Delaunay
triangulation that QuPath's hierarchy provides.

## Adding a command

A command is a QuPath plugin class in `qupath.ext.qust`, registered in
`QuSTExtension.installExtension` with `add(...)`. Shared settings are in
`QuSTSetup` (backed by QuPath preferences). Analyses that need
neighbours use `CellConnections`: the connections stored in the image
data (by **Delaunay Connections**, or by QuPath's deprecated Delaunay
command) if there are any, otherwise
`hierarchy.getSubdivision(cell).getNeighbors(cell)`.

## Release

1.  Set the version in `qupath-extension-qust/build.gradle`,
    `registration/pom.xml` and `docs/conf.py`.
2.  Update `CHANGELOG.md`.
3.  Run `./build-release.sh` and the smoke test.
4.  Attach the three files of `dist/` to the GitHub release.
