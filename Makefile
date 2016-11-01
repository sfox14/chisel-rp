SBT ?= sbt
SBT_FLAGS ?= -Dsbt.log.noformat=true
CFLAGS := -Wall -O3
CC := gcc
LDLIBS := -lm
CHISEL_FLAGS := --wio
staging_dir := ~/.sbt/0.13/staging

top_srcdir ?= .
srcdir ?= src/main/scala/*
csrrcdir ?= src/main/C
top_file := src/main/scala/top.scala
source_files := $(wildcard $(srcdir)/*.scala)

# Clean-up
clean: 
	-rm -f *.h *.hex *.flo *.cpp *.o *.out *.v *.vcd *~
	-rm -rf .emulator verilog benchmark
	sbt clean
	-rm -rf project/target/ target/

cleanall: clean
	-rm -rf $(staging_dir)/*


# Run tests and generate verilog:
# rp.RNSim 	= c++ simulation of Random Projection + NORMA
# rp.RandMain 	= verilog for Random Projection module
# rp.RandTester = c++ simulation of Random Projection
# top 		= verilog for ExanicX4 implementation

run: $(top_file)
	set -e pipefail; $(SBT) $(SBT_FLAGS) "run --genHarness --backend v $(CHISEL_FLAGS)"

# Compile benchmark C implementation
# run: ./benchmark arg1
# arg1 = 1-VSRP+NORMA, 2-NORMA, 3-VSRP

benchmark: src/main/c/rpnorma.c
	$(CC) $(CFLAGS) $^ -o $@ $(LDLIBS)
	


# refernece - http://www.scala-sbt.org/0.13/docs/Command-Line-Reference.html
