# A driver for running 2D alignment using the FijiBento alignment project
# The input is a directory that contains image files (tiles), and the output is a 2D montage of these files
# Activates ComputeSIFTFeaturs -> MatchSIFTFeatures -> OptimizeMontageTransfrom
# and the result can then be rendered if needed
#
# requires:
# - java (executed from the command line)
# - 

import sys
import os
import argparse

from filter_tiles import filter_tiles
from create_sift_features import create_sift_features
from match_sift_features import match_sift_features
from json_concat import json_concat
from optimize_montage_transform import optimize_montage_transform

# Command line parser
parser = argparse.ArgumentParser(description='A driver that does a 2D alignment of images.')
parser.add_argument('tiles_fname', metavar='tiles_json', type=str, 
                	help='a tile_spec file that contains all the images to be aligned in json format')
parser.add_argument('-w', '--workspace_dir', type=str, 
                	help='a directory where the output files of the different stages will be kept (default: current directory)',
                	default='.')
parser.add_argument('-r', '--render', action='store_true',
					help='render final result')
parser.add_argument('-o', '--output_file_name', type=str, 
                	help='the file that includes the output to be rendered in json format (default: output.json)',
                	default='output.json')
parser.add_argument('-j', '--jar_file', type=str, 
                	help='the jar file that includes the render (default: ../target/render-0.0.1-SNAPSHOT.jar)',
                	default='../target/render-0.0.1-SNAPSHOT.jar')
# the default bounding box is as big as the image can be
parser.add_argument('-b', '--bounding_box', type=str, 
                	help='the bounding box of the part of image that needs to be aligned format: "from_x to_x from_y to_y" (default: all tiles)',
                	default='{0} {1} {2} {3}'.format((-sys.maxint - 1), sys.maxint, (-sys.maxint - 1), sys.maxint))

args = parser.parse_args()

print args

# create a workspace directory if not found
if not os.path.exists(args.workspace_dir):
	os.makedirs(args.workspace_dir)

# filter the tiles to the requested bounding box
filter_dir = os.path.join(args.workspace_dir, "filterd")
filter_tiles(args.tiles_fname, filter_dir, args.bounding_box)


# create the sift features of these tiles
sift_dir = os.path.join(args.workspace_dir, "sifts")
create_sift_features(filter_dir, sift_dir, args.jar_file)

# match the features of overlapping tiles
match_dir = os.path.join(args.workspace_dir, "sift_matches")
match_sift_features(filter_dir, sift_dir, match_dir, args.jar_file)

# concatenate all corresponding points to a single file
correspondent_fname = os.path.join(args.workspace_dir, "all_correspondent.json")
json_concat(match_dir, correspondent_fname)

# optimize the 2d layer montage
optmon_fname = os.path.join(args.workspace_dir, "optimized_montage.json")
optimize_montage_transform(correspondent_fname, args.tiles_fname, optmon_fname, args.jar_file)


