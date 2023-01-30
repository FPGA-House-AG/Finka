#include "obj_dir/VFinka_VexRiscv.h"

class VexRiscvTracer : public SimElement
{
public:
	VFinka_VexRiscv *cpu;
	ofstream instructionTraces;
	ofstream regTraces;

	VexRiscvTracer(VFinka_VexRiscv *cpu)
	{
		this->cpu = cpu;
#ifdef TRACE_INSTRUCTION
		instructionTraces.open("instructionTrace.log");
#endif
#ifdef TRACE_REG
		regTraces.open("regTraces.log");
#endif
	}

	virtual void preCycle()
	{
#ifdef TRACE_INSTRUCTION
		if (cpu->writeBack_arbitration_isFiring)
		{
			instructionTraces << hex << setw(8) << cpu->writeBack_INSTRUCTION << endl;
		}
#endif
#ifdef TRACE_REG
		if (cpu->lastStageRegFileWrite_valid == 1 &&
			cpu->lastStageRegFileWrite_payload_address != 0)
		{
			regTraces << " PC " << hex << setw(8) << cpu->__PVT__memory_to_writeBack_PC << " : reg[" << dec << setw(2) <<
			(uint32_t)cpu->lastStageRegFileWrite_payload_address << "] = "
			<< hex << setw(8) << cpu->lastStageRegFileWrite_payload_data
			<< endl;
		}
#endif
	}
};
