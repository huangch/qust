Cell-cell interaction analysis
==============================

ST shows how cells communicate and influence each other: the gene expression and the spatial organisation together give the
interactions between the cells (paper, *Cell-cell interaction analysis*, Fig. 7). QuST uses the ligand-receptor (LR) pairs of
`CellTalkDB <http://tcm.zju.edu.cn/celltalkdb/>`_, a manually curated database of 3,398 human and 2,033 mouse LR pairs. The
human table is ``data/cci/human_lr_pair.csv`` of the release (set by the *CCI dataset file* preference).

QuST uses the result of the cellular spatial profiling to compute the interaction, with the neighbourhood of the cells within
the regions of interest (:doc:`spatial_profiling`).

Algorithm
---------

For each cell *c* and each chosen LR pair (*l*, *r*) (paper, Algorithm 3), over all neighbours *c'* of *c* within the edge distance
*d*:

* the ligand value of the pair is the sum of the ligand expression of *c* times the receptor expression of *c'*;
* the receptor value is the sum of the receptor expression of *c* times the ligand expression of *c'*;

both divided by the number of neighbours. When the receptor cell is analysed, all ligand cells within the neighbouring distance
contribute. The heat map of a pair (Fig. 7 shows CEACAM6-EGFR) gives the intensity of the interaction for the cluster of cells.

Cell-Cell Interaction Analysis
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Uses the ligand-receptor table of the *CCI dataset file* preference. Only the pairs of which both
genes are in the data are computed. Each gene value is divided by the sum of the values of the cell, so the analysis
works on relative expression.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``layers``
    - 1
    - Number of neighbour layers
  * - ``ligand_receptor``
    - ligand
    - ``ligand``: the ligand of the cell with the receptors of its neighbours; ``receptor``: the reverse
  * - ``scale``
    - 1000
    - Factor applied to the result

The results are the measurements ``cci:ligand:<ligand>-<receptor>`` and ``cci:receptor:<ligand>-<receptor>`` of each cell.
