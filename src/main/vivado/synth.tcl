# 
# Synthesis script
#  -- variables are set in compile.tcl 
# 

create_project -in_memory -part xcku035-fbva676-2-e
set_property target_language Verilog [current_project]

add_files "verilog/"
add_files "src/main/verilog/"

#read_verilog -library xil_defaultlib {
#  ./verilog/BinaryRP.v
#  ./src/main/verilog/DualPortLutRam.v
#}

synth_design -top $topMod -part xcku035-fbva676-2-e -flatten_hierarchy none
write_checkpoint -noxdef -force "${::output_dir}/${topMod}_synth.dcp"
report_utilization -hierarchical -file "${::output_dir}/${topMod}_hierarchical_utilisation_synth.rpt"
report_utilization -file "${::output_dir}/${topMod}_utilisation_synth.rpt"
