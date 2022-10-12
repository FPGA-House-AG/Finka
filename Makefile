.ONESHELL:

.PHONY: rtl debug_in_sim mrproper

# in background run batched program load and run via GDB
# while the simulator is running
# afterwards manually inspect waveform using "make waveform"
debug_in_sim:
	rm -rf sbt.log
	make sim_batch_debug &
	PIDGDB=$$!
	echo PIDGDB=$$PIDGDB
	make sim
	echo "\nTEST RESULT"=$$?
	#kill -9 $$PIDGDB

# load and run via GDB in batch mode, after detecting the JTAG TCP
sim_batch_debug:
	set -e
	# @TODO maybe do not rely on log, but on netstat -tln | grep port?
	tail -F sbt.log | sed '/WAITING FOR TCP JTAG CONNECTION/ q' > /dev/null
	make -C software/c/finka/hello_world   batch_debug    DEBUG=yes

# build program for SoC, and RTL of SoC
rtl:
	set -e
	# make -j8 -C software/c/finka/hello_world clean
	# make -j8 -C software/c/finka/hello_world clean all DEBUG=yes
	sbt "runMain finka.FinkaWithMemoryInit"

repl:
	sbt "~runMain finka.FinkaWithMemoryInit"

# run in terminal #1
sim: #use_dev_spinal
	set -e
	rm -rf sbt.log
	make -j8 -C software/c/finka/hello_world clean
	make -j8 -C software/c/finka/hello_world all DEBUG=yes
	(sbt "runMain finka.FinkaSim" | tee sbt.log)
	# @TODO this one could drive some automated tests
	#(sbt "test:runMain vexriscv.FinkaSim" | tee sbt.log)

# run in terminal #2
# load and run via GDB in batch mode
debug:
	set -e
	tail -F sbt.log | sed '/WAITING FOR TCP JTAG CONNECTION/ q' > /dev/null
	make -C software/c/finka/hello_world   debug    DEBUG=yes

# run in terminal #3
waveform:
	gtkwave -f simWorkspace/Finka/test.vcd -a 00C000000.gtkw &

# extract SpinalHDL version from ../SpinalHDL/project/Version.scala
# then fill this in into build.sbt
use_dev_spinal:
	git show origin/dev:build.sbt > build.sbt
	VERSION=1.6.5
	VERSION=`grep -e 'major.*=' ../SpinalHDL/project/Version.scala | sed 's@.*major.*=.*"\(.*\)"$$@\1@'`
	@echo "Found ../SpinalHDL/ version $$VERSION, using this for our build.sbt"
	sed -i "s@val.*spinalVersion.*=.*@val spinalVersion = \"$${VERSION}\"@;" build.sbt
	@# @TODO: get this from ../SpinalHDL/project/Version.scala:7:  private val major = "1.6.5"

use_upstream_spinal:
	git checkout build.sbt

mrproper:
	sbt clean
	rm -fr ~/.ivy2 ~/.sbt
