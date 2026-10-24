Cell clustering analysis
========================

The functioning of biological processes such as cell-cell interactions is related to the local densities and positions of
cells. DBSCAN-CellX (Küchenhoff et al.) is a density-based clustering algorithm, an extension of DBSCAN, to study the cell
densities and positions in cellular monolayers and stratified epithelia. One application is the Tertiary Lymphoid Structures (TLS),
which are associated with a favourable cancer prognosis, and whose density can be evaluated on H&E-stained digital slides
(paper, *Cell clustering analysis*, Fig. 8).

QuST integrates DBSCAN-CellX: given the classes of the cells, it computes the local densities and positions of the cells. The
results are measurements in the detection table, so they can be inspected with the native QuPath functions of measurement maps
and exported. In the example of the paper, clusters of 50 or more lymphocytes were identified, and the *edge degree* of a
selected cell shows its distance to the edge of its lymphocyte cluster.

DBSCAN-CellX
------------

Runs `DBSCAN-CellX <https://github.com/GrawLab/DBSCAN-CellX>`_ (Python, in the environment of the preferences) on the
selected cells (or the cells of the selected annotation; not both). It needs the measurements ``Cell: Max diameter µm``
and ``Cell: Min diameter µm`` to know the cell size.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``edgeMode``
    - true
    - Detect the edge of the clusters (gives the edge degree)
  * - ``angelParam``
    - 140
    - Threshold angle for the edge correction, in degrees; the smaller, the more cells are edge cells
  * - ``keepUncorr``
    - true
    - Also keep the uncorrected cluster positions
  * - ``samplingNum``
    - 10000
    - Number of cells to analyse (0: all). Large numbers need much time and memory
  * - ``label``
    - dbscan-cellx
    - Prefix of the measurements

The measurements of each cell are ``<label>:Cluster_ID``, ``<label>:Cluster_Position`` (0 centre, 1 edge, 2 other),
``<label>:Edge_Degree`` (the distance to the edge of the cluster), ``<label>:Cells_in_Cluster``, ``<label>:Cells_in_Image`` and
``<label>:Uncorrected_Cluster_Position``.
