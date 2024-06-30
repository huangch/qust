[![Forum](https://img.shields.io/badge/forum-image.sc-green)](https://forum.image.sc/tag/qupath)

# QuST: QuPath Extension for Integrative Whole Slide Image and Spatial Transcriptomics Analysis

Welcome to the QuST extension for [QuPath](http://qupath.github.io)!

We introduce QuST, a QuPath extension designed to bridge the gap between H&E WSI and ST analyzing tasks. 
Recently, various technologies have been introduced into digital pathology, including artificial intelligence (AI) driven methods, in both areas of pathological whole slide image (WSI) analysis and spatial transcriptomics (ST) analysis. AI-driven WSI analysis utilizes the power of deep learning (DL), expands the field of view for histopathological image analysis.  On the other hand, ST bridges the gap between tissue spatial analysis and biological signals, offering the possibility to understand the spatial biology. However, a major bottleneck in DL-based WSI analysis is the preparation of training patterns, as hematoxylin & eosin (H&E) staining does not provide direct biological evidence, such as gene expression, for determining the category of a biological component. On the other hand, as of now, the resolution in ST is far beyond that of WSI, resulting the challenge of further spatial analysis. Although various WSI analysis tools, including QuPath, have cited the use of WSI analysis tools in the context of ST analysis, its usage is primarily focused on initial image analysis, with other tools being utilized for more detailed transcriptomic analysis. As a result, the information hidden beneath WSI has not yet been fully utilized to support ST analysis.

The current version is written for QuPath v0.5.1

See what's new in the [changelog](CHANGELOG.md);

![Workflow](./artifacts/qustwsi_diagram.png)

QuST workflow includes: (a) users begin by importing ST data into QuPath using QuST. This step may require additional spatial alignment data which can be obtained via FIJI, if the user is working on 10x Xenium dataset (see text). (b) once the ST data is successfully loaded, users can perform analysis and visualization via QuPath and QuST. (c) given the biological evidences provided by ST, users can generate the training set for image based cell classification and region segmentation based on H\&E. Finally, the result generated using the DL module can be further analyzed using the functions described in (b).

## Prerequests

- QuST includes a user interface for StarDist for convenience. The user who wishes to use StarDist should download and install it separatly.
- For cell-cell interaction, an additional file, human_lr_pair.csv, is required. The latest version of the file can be obtained from [CellChatDB](https://github.com/jinworks/CellChat/tree/main). An older version can be found [here](./cci_datasets/human_lr_pair.csv). Please cite below publications if you are using their works.

[Suoqin Jin et al., CellChat for systematic analysis of cell-cell communication from single-cell and spatially resolved transcriptomics, bioRxiv 2023](https://biorxiv.org/cgi/content/short/2023.11.05.565674v1)

[Suoqin Jin et al., Inference and analysis of cell-cell communication using CellChat, Nature Communications 2021](https://www.nature.com/articles/s41467-021-21246-9)

- If you are using the QuST deep learning module, Linux environment with GPUs is strongly recommended for the use case.


## Installing

**Better extension support in QuPath v0.5!**
See [readthedocs](https://qupath.readthedocs.io/en/0.5/docs/intro/extensions.html) for details.

To install the QuST extension, download the latest `qupath-extension-qust-[version].jar` file from [releases](https://github.com/qupath/qupath-extension-qust/releases) and drag it onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

You might then need to restart QuPath (but not your computer).


## Citing

If you use this extension, you should cite the original QuST publication

- - Chao-Hui Huang.  
[*QuST: QuPath Extension for Integrative Whole Slide Image and Spatial Transcriptomics Analysis*](https://arxiv.org/abs/2406.01613).  

You should also cite the QuPath publication, as described [here](https://qupath.readthedocs.io/en/stable/docs/intro/citing.html).


## Building

You can build the QuPath QuST extension from source with

```bash
cd qupath-extension-qust
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs`