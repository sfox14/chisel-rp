#
# Build script
#  -- synthesis: synth.tcl 
#  -- place & route: impl.tcl 
#

set ::output_dir "./vivado"
file mkdir $::output_dir
set topMod   		[lindex $argv 0]; # Top module name

source "src/main/vivado/synth.tcl"
source "src/main/vivado/impl.tcl"

