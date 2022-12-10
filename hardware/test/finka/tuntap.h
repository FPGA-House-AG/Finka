
#include <pthread.h>
#include <mutex>
#include <queue>


	
	#include <stdio.h>
	#include <string.h>
	#include <unistd.h>
	#include <errno.h>
	#include <arpa/inet.h>
	#include <net/if.h>
	#include <netinet/ether.h>
	#include <linux/if_packet.h>
	#include <sys/ioctl.h>

struct packet {
	uint8_t payload[1534];
	int length;
};

class TunTapRx : public SimElement {
public:
    CData *_tlast;
    CData *_tready;
    CData *_tvalid;
    CData *_tuser;
    QData *_tkeep;
    WData *_tdata;

	pthread_t rxThreadId;
	pthread_t rxThreadTapId;
	queue<packet> inputsQueue;
	mutex inputsMutex;

    enum State {START, DATA, STOP};
	State state = START;
	struct packet data;
	int remaining;
	uint32_t beat;
	int last_beat;

	TunTapRx(WData *tdata, QData *tkeep, CData *tuser, CData *tlast, CData *tvalid, CData *tready) {
		this->_tlast =  tlast;
		this->_tdata =  tdata;
		this->_tkeep =  tkeep;
		this->_tuser =  tuser;
		this->_tvalid = tvalid;
		this->_tready = tready;
        init();
    }

    virtual ~TunTapRx() {

    }

    void init(){
        *this->_tvalid = 0;
        *this->_tuser = 0;
   		pthread_create(&rxThreadId, NULL, &rxThreadWork, this);
   		pthread_create(&rxThreadTapId, NULL, &rxThreadTapWork, this);
    }

	static void* rxThreadWork(void *self){
		((TunTapRx *)self)->rxThread();
		return NULL;
	}

	void rxThread() {
		int packet_length = 101;
		while(1){
			// { read from TAP here }

			struct packet p;
			for (int i = 0; i < 1534; i++)
			p.payload[i] = (uint8_t)i;
			p.length = packet_length;

			inputsMutex.lock();
			inputsQueue.push(p);
			inputsMutex.unlock();
			sleep(5);
			packet_length += 53;
		}
	}

	static void* rxThreadTapWork(void *self){
		((TunTapRx *)self)->rxThreadTap();
		return NULL;
	}
 
    /*
	sudo tunctl -d tap0
	sudo tunctl -u vivado -t tap0
	sudo ip link set tap0 up
	sudo ip addr add 192.168.255.1 peer 192.168.255.2 dev tap0
	sudo ip addr add local 192.168.255.1 remote 192.168.255.2 dev tap0
	sudo ip link set address aa:bb:cc:11:11:11 dev tap0
	sudo arp -s 192.168.255.2 aa:bb:cc:22:22:22 
    */
	void rxThreadTap() {

		int n;
		int ret = 0;
		int sock;
		char buf[2048];
		struct ifreq ifreq;
		struct sockaddr_ll saddr;

		// create socket
		if((sock = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL))) == -1) {
			ret = errno;
			goto error_exit;
		}

		// bind tap0
		snprintf(ifreq.ifr_name, sizeof(ifreq.ifr_name), "tap0");
		if (ioctl(sock, SIOCGIFINDEX, &ifreq)) {
			ret = errno;
			goto error_exit;
		}

		memset(&saddr, 0, sizeof(saddr));
		saddr.sll_family = AF_PACKET;
		saddr.sll_protocol = htons(ETH_P_ALL);
		saddr.sll_ifindex = ifreq.ifr_ifindex;
		saddr.sll_pkttype = PACKET_HOST;

		if(bind(sock, (struct sockaddr *)&saddr, sizeof(saddr)) == -1) {
			ret = errno;
			goto error_exit;
		}

		// recv data
		while(1) {
			printf("waiting for packet on TAP0\n", n);

			n = recvfrom(sock, buf, sizeof(buf), 0, NULL, NULL);
			printf("%d bytes received\n", n);
		}

	error_exit:
		if (ret) {
			printf("error: %s (%d)\n", strerror(ret), ret);
		}
		close(sock);
		//return ret;
	}

    virtual void postCycle(){
		if (*(this->_tvalid) && *(this->_tready)) {
			//printf("beat = %d/%d\n", beat + 1, last_beat + 1);
			beat++;
			remaining -= 512/8;
		}

    }

    virtual void preCycle(){
		switch(state){
			case START:
				*(this->_tlast) = 0;
				*(this->_tvalid) = 0;
				inputsMutex.lock();
				if(!inputsQueue.empty()){
					data = inputsQueue.front();
					inputsQueue.pop();
					inputsMutex.unlock();
					printf("data.length = %d\n", data.length);
					remaining = data.length;
					beat = 0;
					if (remaining > 0) {
						state = DATA;
					    last_beat = (data.length + 63) / 64 - 1;
					}
				} else {
					inputsMutex.unlock();
					break;
				}
			break;
			case DATA:
				assert(data.length > 0);
				*(this->_tvalid) = 1;
				for (int i = 0, j = 0; i < 16; i++, j += 4) {
					this->_tdata[i] = ((uint32_t)data.payload[j+3] << 24) | ((uint32_t)data.payload[j + 2] << 16) | ((uint32_t)data.payload[j + 1] << 8) | (uint32_t)data.payload[j] << 8;
				}
				*this->_tkeep = 0;
				for (int i = 0; (i < remaining) & (i < 64); i++) {
					*this->_tkeep <<= 1;
					*this->_tkeep |= 1;
				}
				if (beat == last_beat) {
					*(this->_tlast) = 1;
					state = START;
				}
			break;
		}
    }
};