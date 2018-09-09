#
# Implementation script
#  -- variables are set in compile.tcl
#

# Add synthesis .dcp file
add_files -quiet "${::output_dir}/${topMod}_synth.dcp"
link_design -top $topMod -part xcku035-fbva676-2-e

# Read xdc files and pre_impl tcl scripts
read_xdc ./src/main/vivado/clk.xdc

# Source any pre-implementation scripts
#source ./constraints/pre_impl.tcl

opt_design -directive Explore
opt_design -retarget -remap -propconst -sweep
opt_design -directive Explore

place_design
phys_opt_design -directive Explore
phys_opt_design
route_design -directive Explore

write_checkpoint -force "${::output_dir}/${topMod}_post_route.dcp"
report_timing_summary -report_unconstrained -file "${::output_dir}/${topMod}_timing.rpt"
report_utilization -hierarchical  -file "${::output_dir}/${topMod}_hierarchical_utilisation_impl.rpt"
report_utilization -file "${::output_dir}/${topMod}_utilisation_impl.rpt"
