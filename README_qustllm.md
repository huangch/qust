[![Forum](https://img.shields.io/badge/forum-image.sc-green)](https://forum.image.sc/tag/qupath)

# QuST-LLM: Integrating Large Language Models for Comprehensive Spatial Transcriptomics Analysis

Welcome to the QuST-LLM extension for [QuPath](http://qupath.github.io)!

we introduce QuST-LLM, an innovative extension of QuPath that utilizes the capabilities of large language models (LLMs) to analyze and interpret spatial transcriptomics (ST) data. In addition to simplifying the intricate and high-dimensional nature of ST data by offering a comprehensive workflow that includes data loading, region selection, gene expression analysis, and functional annotation, QuST-LLM employs LLMs to transform complex ST data into understandable and detailed biological narratives based on gene ontology annotations, thereby significantly improving the interpretability of ST data. Consequently, users can interact with their own ST data using natural language. Hence, QuST-LLM provides researchers with a potent functionality to unravel the spatial and functional complexities of tissues, fostering novel insights and advancements in biomedical research.

![Forward Analysis](./artifacts/qustllm_diagram1.png)

The QuST-LLM workflow for forward analysis includes the following steps: (a), users begin by importing ST data into QuPath using QuST. This step may require additional spatial alignment data, which can be obtained via FIJI if the user is working with a 10x Xenium dataset (see text for more details). Once the ST data is successfully loaded, users can perform analysis and visualization using QuPath and QuST. (b), QuST-LLM takes the objects selected by the user, including single-cell clusters or regions, performs a series of single-cell data preprocessing steps and then obtains a list of GO terms based on GOEA. (c), the spatial data and GO terms are integrated as biological evidence, which can be interpreted using an LLM service. The final outcomes is presented to the users.

![Backword Analysis](./artifacts/qustllm_diagram2.png)

The QuST-LLM workflow for backward analysis includes the following steps: (a), users begin by providing languages describing the required biological evidences. A LLM service is then interpreting the inputs and obtains the the key terms which may be used to isolate the sub-graph of the GO. (b), QuST-LLM identifies the key genes by using GOEA based on the obtained GO terms. (c), given the ST data which has been loaded into QuST, the users can then identify the cells which may highly relevant to the sentences provided by the users.

The current version is written for QuPath v0.5.1

See what's new in the [changelog](CHANGELOG.md);

