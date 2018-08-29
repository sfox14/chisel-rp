# Chisel Random Projections

This repository contains code for building and testing different hardware implementations of Random Projections using Chisel HDL. Chisel generates a C++ simulation for test purposes as well as Verilog which can be ported across different ASIC/FPGA developer toolchains and platforms.  


## 1. Quick Start:

Open a terminal and run:

```
make test-all 
```

This will run all Chisel C++ simulations. You can run ``` make help ``` for other makefile options. 

## 2. Random Projection:
This repository currently supports:
  * Very Sparse Random Projection (VSRP), as implemented in the [FPT'16 paper](http://phwl.org/papers/rp_fpt16.pdf)

## 3. Build flow:
To generate Verilog for the ```VSRP``` module, run:

```
make verilog TARGET=VSRPMain
```
This will generate a verilog file called ```VSRP.v``` in the ```./verilog``` directory. **Note:** ```TARGET``` must be a scala object residing in ```src/main/scala/``` that implements a ```chiselMain``` function. 

## 4. Repository Structure:

* `src/main: ` Contains the repository source files 
   * `scala: ` 
      * `exanic: ` Source and top module for the ExanicX4 project
      * `rp: ` Contains hardware modules for the supported Random Projection's
      * `utils: ` Contains helper hardware modules and functions
* `src/test/scala: ` Chisel test suites.
    * `TestSuite.scala: ` base class
    * `VSRPSuite.scala: ` test suite for VSRP hardware module
    * etc.
* `src/resources: ` Repository utility scripts and files.
    * `make_rm.py: ` python script to generate the VSRP random matrix
    * `summarizeResults.R: ` R script for evaluating VSRP+NORMA accuracy
    * `params.csv` `mem.csv` `seeds.csv: ` inputs for VSRP and VSRP+NORMA modules 
* `src/data: ` Required input datasets.

## 5. Dependencies:
* python2.7
* sbt 0.13
* java openjdk-8

To install the correct version of sbt, run:
```
echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
sudo apt-get update
sudo apt-get install sbt=0.13
```



