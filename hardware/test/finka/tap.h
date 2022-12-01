

struct packet {
	uint8_t payload[1534];
	int length;
};

class TapRx : public TimeProcess{
public:

	CData *rx;
	uint32_t uartTimeRate;
	TapRx(CData *rx, uint32_t uartTimeRate){
		this->rx = rx;
		this->uartTimeRate = uartTimeRate;
		schedule(uartTimeRate);
	}

	enum State {START, DATA, STOP};
	State state = START;
	struct packet data;
	uint32_t counter;


	virtual void tick(){
		switch(state){
			case START:
				if (*rx == 0){
					state = DATA;
					counter = 0;
					//data = 0;
					schedule(uartTimeRate*5/4);
				} else {
					schedule(uartTimeRate/4);
				}
			break;
			case DATA:
				//data |= (*rx) << counter++;
				if(counter == 8){
					state = STOP;
				}
				schedule(uartTimeRate);
			break;
			case STOP:
				if(*rx){
					//cout << data << flush;
				} else {
					cout << "UART RX FRAME ERROR at " << time << endl;
				}

				schedule(uartTimeRate/4);
				state = START;
			break;
		}
	}
};

#include<pthread.h>
#include <mutex>
#include <queue>

class TapTx : public TimeProcess{
public:

	CData *_tlast;
	CData *_tready;
	CData *_tvalid;
	CData *_tuser;
	QData *_tkeep;
	WData *_tdata;
	uint32_t uartTimeRate;

	enum State {START, DATA, STOP};
	State state = START;
	struct packet data;
	int remaining;
	uint32_t counter;
	pthread_t inputThreadId;
	queue<packet> inputsQueue;
	mutex inputsMutex;

    //VL_IN8(framerxs_tvalid,0,0);
    //VL_OUT8(framerxs_tready,0,0);
    //VL_IN8(framerxs_tlast,0,0);
    //VL_IN8(framerxs_tuser,0,0);
    //VL_INW(framerxs_tdata,511,0,16);
    //VL_IN64(framerxs_tkeep,63,0);

	//tdata is a pointer to an array of 16 words
	TapTx(WData *tdata, QData *tkeep, CData *tuser, CData *tlast, CData *tvalid, CData *tready, uint32_t uartTimeRate) {
		this->_tlast =  tlast;
		this->_tdata =  tdata;
		this->_tkeep =  tkeep;
		this->_tuser =  tuser;
		this->_tvalid = tvalid;
		this->_tready = tready;
		this->uartTimeRate = uartTimeRate;
		schedule(uartTimeRate);
		pthread_create(&inputThreadId, NULL, &inputThreadWrapper, this);
		*tvalid = 0;
		*tlast = 0;
	}

	static void* inputThreadWrapper(void *uartTx){
		((TapTx*)uartTx)->inputThread();
		return NULL;
	}

	void inputThread() {
		int packet_length = 1;
		while(1){
			// read from TAP
			struct packet p;
			for (int i = 0; i < 1534; i++)
			p.payload[i] = (uint8_t)i;


			p.length = packet_length;
			inputsMutex.lock();
			inputsQueue.push(p);
			inputsMutex.unlock();
			sleep(100);
			packet_length += 5;
		}
	}

	virtual void tick(){
		switch(state){
			case START:
									*(this->_tlast) = 0;
					*(this->_tvalid) = 0;
				inputsMutex.lock();
				if(!inputsQueue.empty()){
					data = inputsQueue.front();
					printf("data.length = %d\n", data.length);
					inputsQueue.pop();
					inputsMutex.unlock();
					remaining = data.length;
					counter = 0;
					if (remaining > 0) {
						state = DATA;
					}
					schedule(uartTimeRate);
				} else {
					inputsMutex.unlock();
					schedule(uartTimeRate* 1000000);
					break;
				}
			//break;
			case DATA:
				printf("counter = %d\n", counter);
				*(this->_tvalid) = 1;
				for (int i = 0, j = 0; i < 16; i++, j += 4) {
					this->_tdata[i] = ((uint32_t)data.payload[j+3] << 24) | ((uint32_t)data.payload[j + 2] << 16) | ((uint32_t)data.payload[j + 1] << 8) | (uint32_t)data.payload[j] << 8;
				}
				*this->_tkeep = 0;
				for (int i = 0; (i < remaining) & (i < 64); i++) {
					*this->_tkeep <<= 1;
					*this->_tkeep |= 1;
				}

				if (*(this->_tready)) {
					counter++;
					remaining -= 512/8;
				}
				if (counter == ((data.length + 15)/16)) {
					*(this->_tlast) = 1;

					state = START;
				}
				schedule(uartTimeRate);
			break;
			case STOP:
				//schedule(uartTimeRate);
				//if (data) {
					//free(data);
					//data = NULL;
				//}
									*(this->_tlast) = 0;
					*(this->_tvalid) = 0;

				state = START;
			break;
		}
	}
};
