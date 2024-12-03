.. qust documentation master file, created by
   sphinx-quickstart on Sat Sep 21 13:44:35 2024.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

:Authors:
    Chao-Hui Huang

:Version: 1.0 of 2024/10/24
:Dedication: To Everyone contribution to QuST

Image Alignment
===============

Load H&E Images into FIJI
*************************

First, click "File -> Import -> Bio-Formats" and select the corresponding H&E image file.

.. image:: artifacts/fiji-file-import-bioformats.png
   :width: 400pt


In Bio-Formats Import Options, using options as showing below.

.. image:: artifacts/fiji-bioformats-import-options.png
   :width: 400pt


Next, select the Series Number. The higher series number, e.g., Series 1, the higher resolution. However, higher resolution may result OOM (out-of-memory) if the resource is limitied. On the other hand, lower resolution may limit the accuracy of the following preocess. To author's experience, Series 3 is usually a good balance.

.. image:: artifacts/fiji-bioformats-series-options.png
   :width: 400pt


Click Ok button, the chosen H&E whoile slide image will be loas as a RGB stack in FIJI. Note that in this example, we used `FFPE Human Breast with Custom Add-on Panel
provided by 10x Genomics
<https://www.10xgenomics.com/datasets/ffpe-human-breast-with-custom-add-on-panel-1-standard>`_. Currently, 10x Genomics has performmed image registration for all of the H&E images in their database. Thus, if the user is using their image as the example, some unexpected noises (e.g., black strips at the edges of the H*E image) have to be removed beforehand.

.. image:: artifacts/fiji-wsi-he-stack.png
   :width: 400pt


The type of the loaded H&E image is RGB stack. It is necessary to convert the type to RGB Color by selecting Image->Type->RGB Color.

.. image:: artifacts/fiji-image-type-rgb-color.png
   :width: 400pt


H&E Color Deconvolution
***********************

AT this step, we will perform color deconvolution for extracting nuiclei signals (which is highly related to hematoxylin staing of an H&E image). First, use Image->Colod->Color Deconvolution function in FIJI...

.. image:: artifacts/fiji-image-color-color-deconvolution.png
   :width: 400pt

...and select the desired color space. the vector of the chosen color space should be selected accordingly. In this case, we used "H&E".

.. image:: artifacts/fiji-color-deconvolution-options.png
   :width: 400pt


The RGB Color image is deconvoluted. 3 additional 8 bit images are generated.

.. image:: artifacts/fiji-wsi-he-deconvoluted.png
   :width: 400pt

Since only the hematoxylin channel is needed, the rest images can be closed. Here the LUT is also replaced by Gray for better visually investigation.

.. image:: artifacts/fiji-wsi-hematoxylin.png
   :width: 400pt

The hematoxylin has to be inverted as showning below:

.. image:: artifacts/fiji-wsi-hematoxylin-invteted.png
   :width: 400pt

Below is an additional process which may not applicable for all cases. The user may have to remove the strips on the edges of the image if you are using H&E images provided by 10x Genomics, since 10x Genomics have performmed alignment for all of the H&E images in their dataset. This won't be the case for most of the users.

.. image:: artifacts/fiji-wsi-hematoxylin-invteted-cleaned.png
   :width: 400pt

The residual signal in the background needs to be remove. This step can be done by performming Process->Substrate background...

.. image:: artifacts/fiji-wsi-hematoxylin-inverted-cleaned-background-removed.png
   :width: 400pt

Then, we perform histogram equalization by using Image->Adjust->Brightness/Contrast

.. image:: artifacts/fiji-image-adjust-brightness-contrast.png
   :width: 400pt

Hit the Auto button, the histogram equalization will be executed accordingly. 

.. image:: artifacts/fiji-wsi-hematoxylin-inverted-cleaned-background-removed-equalized.png
   :width: 400pt

Hit "Apply" and then hit "Ok" to save the equalized signal. The nuclei signal obtained from H&E image thus is ready at this stage.

Load DAPI Images into FIJI
**************************

Click "File -> Import -> Bio-Formats" and select the corresponding DAPI image file. The corresponding MIP (maximum intensity projection) is used in this case.

.. image:: artifacts/fiji-file-import-bioformats-dapi.png
   :width: 400pt

Using below options to load DAPI image.

.. image:: artifacts/fiji-bioformats-import-options-dapi.png
   :width: 400pt

Select the series number same as when loading the corresponding H&E image.

.. image:: artifacts/fiji-bioformats-series-options-dapi.png
   :width: 400pt

It is normal if you see a whole black image right after loading the chosen DAPI. 

.. image:: artifacts/fiji-wsi-dapi.png
   :width: 400pt

Perform histogram equalization by Image->Adjust->Brightness/Contrast, and click Auto, then hit Apply.

.. image:: artifacts/fiji-wsi-dapi-brightnesscontrast.png
   :width: 400pt

We also convert the LUT to gray in order to perform better visual investigation.

.. image:: artifacts/fiji-wsi-dapi-equalized-gray.png
   :width: 400pt

In most cases, DAPI image is stored in 16-bit format. The image has to be converted into 8-bit format by clicking Image->Type->8-bit.

.. image:: artifacts/fiji-wsi-dapi-8bit.png
   :width: 400pt

At this stage, DAPI image is loaded. 

.. image:: artifacts/fiji-hne-dapi-loaded.png
   :width: 400pt

Linear Image Stack Alignment using SIFT
***************************************

First, generate a image stack using the above obtained hematoxylin and DAPI channels by clicking Image->Stacks->Image to Stack.

In most cases, the obtained H&E image (here, the hematoxylin channel) and the DAPI image havve different dimension. In this case, the stacking method needs to be "Copy (top-left)". In addition, you may want to enable "keep source images" since you may need image dimension.

.. image:: artifacts/fiji-image-to-stack-option.png
   :width: 400pt

Make sure the hematoxylin channel is at the first position of the generated image stack.

.. image:: artifacts/fiji-hematoxylin-DAPI-stack.png
   :width: 400pt

Next, start SIFT process by clicking Plugins->Registration->Linear Stack Alignment SIFT. Select "Affine" in the "expect transformation" option, and enable "show transformation matrix".

.. image:: artifacts/fiji-align-stack-options.png
   :width: 400pt

Click Ok, in a moment, the result will be generated. You can zoom-in into the detail of the image and observe the result of SIFT.

.. image:: artifacts/fiji-align-stack-results.png
   :width: 400pt

If the above hematoxylin and DAPI channels have different dimension, it is necessary to crop the image. This step can be done by making an rectangular annotation. The top-left of the rectangular annotation is aligned to the top-left of the image stack. The width and weight of the rectangular annotation are the same as the hematoxylin channel. The trick is, using the mouse creating the rectangular annotation, then, fine-tunning the width and height of the rectangular annotation by holding ALT key and pressing top-down/left-right keys.

.. image:: artifacts/fiji-align-stack-results-annotated.png
   :width: 400pt

Once the rectangular annotation is generated, click Image->Crop to crop the image stack so that the dimension of the image stack is restored.

.. image:: artifacts/fiji-align-stack-results-annotated-cropped.png
   :width: 400pt

You can zoom-in and inspect the result of linear image registration 

.. image:: artifacts/linear_registration_results.gif
   :width: 400pt

Now, switch to the Log window, click on the obtained transformation matrix, hit right mouse button and hit "Copy".

.. image:: artifacts/fiji-align-stack-transformation-matrix.png
   :width: 400pt

At this stage, the linear transformation matrix is obtained.

Now, let's import the transformation matrix to QuST environment. Start QuPath, and open the corresponding H&E image.

.. image:: artifacts/quipath-hne.png
   :width: 400pt

In a QuPath environment with QuST, click Extension->QuST Analysis Toolbox->Pixel Size Calibration by Xenium Affine Matrix, fill the fields as following:

* Xenium outout folder: the "outs" folder that was delivered by the vendor.
* DAPI image pixel size: currently this value is 0.2125, which may or may not consist with your data. Please consult your vendor.
* Image series: using the image series id used when processing H&E and DAPI images.
* Affine Matrix: paste the affine matrix obtained in the previous step.

Then, hit "Run" button. The corresponding pixel size and affine matrix for linear registration will be saved. 

.. image:: artifacts/qupath-hne-pixel-size-calibration.png
   :width: 400pt

Non-linear Image Stack Alignment using bUnwrap
**********************************************

Switch back to FIJI at the result of linear image registration, click Image->Stacks->Stack to Images, in order to split the stack. You may have to record the order of the images. Note that the first image is hematoxylin channel obtained from H&E, and the second image is DAPI aligned to hematoxylin channel.

.. image:: artifacts/fiji-stack-to-images.png
   :width: 400pt

Once the two images are splitted from the image stack, go to Plugins->Registration->bUnwrapJ.

In the bUnwrapJ options, 

Source Image: select the hematoxylin channel 
Target Image: select the aligned DAPI channel
Registration Mode: Mono
Initial Deformation: Very Fine (this option might be veriate but personally I use Very Fine).
Final Deformation: Super Fine (this option might be veriate but personally I use Super Fine).
Verbose: checked (for observing the further details of the registration outcome)
Save Transformation: checked

.. image:: artifacts/fiji-bunwrapj-options.png
   :width: 400pt

Click "OK", and observe the Log window. After a while, a "Save_direct_transformation" File Dialog showed. Name the file as "direct_transf.txt" and save the file to the "outs" folder.

.. image:: artifacts/fiji-bunwrapj-save-direct-transf.png
   :width: 400pt

At this stage, the nonlinear registration is completedtack of 5 images will be shown on the screen. Note that the author has experienced a bug in FIJI when flipping between the frames in the image stack. You may want to switch the resulted ime stack into 8-bit for cancelling the bug.

.. image:: artifacts/fiji-bunwrapj-results.png
   :width: 400pt

In the result stack, frame 4 and 5 are particually useful. They give you the idea how the bUnwrap decided the transformation over the whole slide.

.. image:: artifacts/fiji-bunwrapj-results-vectors.png
   :width: 400pt

.. image:: artifacts/fiji-bunwrapj-results-grids.png
   :width: 400pt

You can zoom-in into the same spot when inspecting the linear registration outcome.

.. image:: artifacts/nonlinear_registration_results.gif
   :width: 400pt

Load Xenium data into QuST
**************************

Switch back to QuPath. First, we have to perform nuclei detection using the approach you prefer.

.. image:: artifacts/qupath-cell-detection.gif
   :width: 400pt

Go to Extension->QuST Analysis Toolbox->10x Xenium Annotation for loading the xenium data. Give the correct "outs" folder in the Xenium directory. Leave the rest parameters as default unless you know what do they mean. 

.. image:: artifacts/qupath-xenium-loader.gif
   :width: 400pt

Click "Run"...

Finally the xenoium single cell data, including their transcriptomics and the subtypes will be loaded.

.. image:: artifacts/qupath-xenium-loader-results.gif
   :width: 400pt

The End


