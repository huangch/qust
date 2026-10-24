# Python scripts

| Script | Used by | Purpose |
|---|---|---|
| `train.py` | you | trains a cell or region classifier on the images exported by QuST and a QuPath measurement table |
| `classification.py` | QuST | applies a trained model (**Object Classification**, **Region Segmentation**), estimates the stain matrix for normalization |

```bash
pip install -r requirements.txt
python train.py my_model -i sample1 -d sample1.txt -o models -p 0.2125 -m resnet50
python train.py --help
```

`-i` takes the image folders written by **Export Images for Object/Region Segmentation**, `-d` the matching measurement
tables saved from QuPath (same name as the folder; the `Object ID` and `Classification` columns are used), `-p` the pixel
size of the images in µm, and `-n` has to be given if the images were exported with stain normalization. The model is
written as `<name>.final.pt`; the best checkpoint of the validation set is kept as `<name>-<epoch>-<accuracy>.ckpt.pt`.
Copy the model to the model directory set in the QuPath preferences.

`classification.py` is started by QuPath, which needs the **QuST script directory** preference to point to this folder.
Without a GPU it runs on the CPU.
