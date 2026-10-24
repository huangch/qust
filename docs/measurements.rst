Measurements and classes
========================

What the commands of QuST write into the QuPath objects. Measurements are shown in **Measure → Show detection measurements**
and can be mapped with **Measure → Show measurement maps**. Running a command again with the same name or ID replaces its
earlier measurements.

.. list-table::
  :header-rows: 1
  :widths: 34 26 40

  * - Name
    - Written by
    - Meaning
  * - ``transcript:<gene>``
    - the Xenium, CosMx and Visium loaders
    - expression of the gene in the cell, as in the data folder
  * - ``csp:cls:<id>``
    - Cell Spatial Profiling by Classification
    - layer of the cell inside the cluster: 1 at the border, 2 for the next layer, ...; absent outside the cluster
  * - ``sctc:count:<class>``
    - Neighboring Cell Type Composition
    - number of cells of the class among the neighbours of the cell (and the cell itself)
  * - ``sctc:prob:<class>``
    - Neighboring Cell Type Composition
    - the same divided by the number of neighbours
  * - ``cci:<ligand|receptor>:<ligand>-<receptor>``
    - Cell-Cell Interaction Analysis
    - interaction score of the pair for the cell
  * - ``<label>:Cluster_ID``, ``<label>:Cluster_Position``, ``<label>:Edge_Degree``, ``<label>:Cells_in_Cluster``,
      ``<label>:Cells_in_Image``, ``<label>:Uncorrected_Cluster_Position``
    - DBSCAN-CellX
    - cluster of the cell, position in the cluster (0 centre, 1 edge, 2 other), edge degree (the distance to the edge of
      the cluster), and sizes
  * - ``objcls:<model>:prob:<label>``
    - Object Classification (*Add prediction/probability*)
    - probability of each label

.. list-table::
  :header-rows: 1
  :widths: 34 66

  * - Class
    - Meaning
  * - the cell type of the loaders
    - the graph-based cluster of the ST data of the cell (Xenium, Visium)
  * - ``objcls: <model>: <label>``
    - the cell type predicted by Object Classification
  * - the class of a region annotation
    - the region class predicted by Region Segmentation
  * - ``<prefix>-<row>-<column>``
    - a pseudo spot of Pseudo Spot Generation
