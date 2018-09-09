SBT ?= sbt
SBT_FLAGS ?= -Dsbt.log.noformat=true
CHISEL_FLAGS := --wio
staging_dir := ~/.sbt/0.13/staging

CFLAGS := -Wall -O3
CC := gcc
LDLIBS := -lm

srcdir ?= src/main/scala/*
csrcdir ?= src/main/c
source_files := $(wildcard $(srcdir)/*.scala)

TCL_SCRIPT := src/main/vivado/compile.tcl

TARGET := VSRPSuite

# Generate verilog
.PHONY: all verilog vivado
all: help 
	set -e pipefail; $(SBT) $(SBT_FLAGS) "run --genHarness --backend v --targetDir verilog $(CHISEL_FLAGS)"

# Generate $(TARGET) verilog
verilog:
	set -e pipefail; $(SBT) $(SBT_FLAGS) "run-main $(TARGET) --genHarness --backend v --targetDir verilog $(CHISEL_FLAGS)"

# Run $(TARGET) Chisel test
test:
	$(SBT) "testOnly *$(TARGET)"

# Run all Chisel tests
test-all:
	$(SBT) test

# Build vivado project
vivado:
	mkdir -p vivado/ && vivado -mode batch -source $(TCL_SCRIPT) -log ./vivado/vivado.log -journal ./vivado/vivado.jou -tclargs $(TARGET)


# Compile the FPT'16 CPU benchmarks
# run: ./vsrp-cpu arg1
# arg1 = 1-VSRP+NORMA, 2-NORMA, 3-VSRP
.PHONY: vsrp-cpu
vsrp-cpu: src/main/c/vsrpnorma.c	
	$(CC) $(CFLAGS) $^ -o $@ $(LDLIBS)

# Clean-up
clean: 
	-rm -f *.h *.hex *.flo *.cpp *.o *.out *.v *.vcd *~ 
	-rm -f vsrp-cpu
	sbt clean
	-rm -rf project/ target/ test-outputs/ verilog/

cleanall: clean
	-rm -rf $(staging_dir)/*

ECHO := @echo

.PHONY: help
help::
	$(ECHO) "Makefile Usage:"
	$(ECHO) "	make"
	$(ECHO) "		Command to search and select the chiselMain function to run"
	$(ECHO) ""
	$(ECHO) "	make verilog TARGET="
	$(ECHO) "		Command to generate verilog for target. TARGET argument is required"	
	$(ECHO) ""
	$(ECHO) "	make test TARGET="
	$(ECHO) "		Command to run target Chisel C++ simulation. TARGET argument is required"
	$(ECHO) ""
	$(ECHO) "	make test-all"
	$(ECHO) "		Command to run all Chisel C++ test suites"
	$(ECHO) ""
	$(ECHO) "	make vsrp-cpu"
	$(ECHO) "		Command to compile the FPT'16 CPU benchmarks"
	$(ECHO) ""
	$(ECHO) "	make clean"
	$(ECHO) "		Command to remove generated files"
	$(ECHO) ""
	$(ECHO) "	make cleanall"
	$(ECHO) "		Command to remove all Chisel files"
	$(ECHO) ""
	$(ECHO) "	Arguments:"
	$(ECHO) "	----------"
	$(ECHO) "	TARGET:		target Verilog, TestSuite or Vivado top module name"							
	$(ECHO) ""
	$(ECHO) "	Supported Verilog targets:"
	$(ECHO) "	--------"
	$(ECHO) "	rp.VSRPMain:		Very Sparse Random Projection (VSRP), FPT'16" 
	$(ECHO) "	exanic.VSRPNormaMain:	Low-latency VSRP+Norma on ExanicX4"
	$(ECHO) ""	
	$(ECHO) "	Supported TestSuite targets:"
	$(ECHO) "	--------"
	$(ECHO) "	VSRPSuite:		Very Sparse Random Projection (VSRP) test" 
	$(ECHO) "	VSRPNormaSuite:		VSRP+Norma test"				
	$(ECHO) ""


