Cellular neighborhood analysis
==============================

Living tissue is made of communities of cells in spatial structures, and the spatial context is critical for the understanding
of tissue biology. Inspired by the neighbourhood analysis of imaging mass cytometry (HistoCAT) and of spatial omics, QuST
provides the Cellular Neighborhood Analysis (paper, Algorithm 2, Fig. 6).

Algorithm
---------

For each chosen cell *c*, the neighbours of *c* within the edge distance *d* (a number of layers of the Delaunay graph, see
:doc:`spatial_profiling`) are collected. The number of neighbours of each cell type *t* is counted, and divided by the number of
neighbours: the probability that the type *t* exists in the neighbourhood of *c*.

The paper highlights the epithelial and tumour cells, and maps the number of lymphocytes in the neighbourhood of each cell; with
the tumour regions overlaid, this gives the likelihood of a tumour cell to have at least one lymphocyte around it.

Neighboring Cell Type Composition
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Counts the cells of each class among the neighbours of a cell (and the cell itself).

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``layers``
    - 1
    - Number of neighbour layers
  * - ``normalize``
    - true
    - Also write the fraction of each class (count divided by the number of neighbours)

The results are ``sctc:count:<class>`` (the count) and ``sctc:prob:<class>`` (the fraction), for each class of the cells.
