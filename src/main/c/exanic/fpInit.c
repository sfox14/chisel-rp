/** This file sends some data down to the exanic interface and reads the result
 */
#include <stdio.h>
#include <string.h>
#include <signal.h>
#include <exanic/exanic.h>
#include <exanic/fifo_rx.h>
#include <exanic/fifo_tx.h>
#include <exanic/util.h>
#include <exanic/port.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <stdlib.h>

#define REG_CNTRL         0x000f
#define REG_ERR           0x000f
#define BITWIDTH          24
#define FRACWIDTH         16

// Measures latency of user_application, not working as network card (no listener on port 1)

int keep_running = 1;


void sig_handler(int sig)
{
    keep_running = 0;
}

void reset( volatile uint32_t * application_registers ) {
  application_registers[REG_CNTRL] = 7;
  sleep(1);
  application_registers[REG_CNTRL] = 0;
}

void genRnd( char * tx_buf, size_t tx_buf_size ) {
  int idx;
  for ( idx = 0; idx < tx_buf_size; idx++ )
    tx_buf[idx] = rand();
}

void sendPkt( exanic_tx_t * tx, char * tx_buf, size_t tx_buf_size) {
  printf("Sending frame:\n");
  size_t ret;
  ret = exanic_transmit_frame( tx, tx_buf, tx_buf_size );
  printf("ret = %d\n", ret);
}

size_t recvPkt( exanic_rx_t * rx, char * rx_buf, size_t rx_buf_size ) {
  int idx;
  for ( idx = 0; idx < rx_buf_size; idx++ )
    rx_buf[idx] = 0;
  return exanic_receive_frame(rx, rx_buf, rx_buf_size, NULL);
}


int main(int argc, char *argv[])
{
    exanic_t *exanic;
    exanic_rx_t *rx;
    exanic_tx_t *tx;
    volatile uint32_t *application_registers;
    char rx_buf[2048];
    char tx_buf[60]; //generate 768 bytes
    char * exanicStr = "exanic0";
    int size = 0;
    int rxIdx = 0;

    
    if (argc != 1)
        goto usage_error;

    
    if ((exanic = exanic_acquire_handle(exanicStr)) == NULL)
    {
        fprintf(stderr, "%s: %s\n", exanicStr, exanic_get_last_error());
        return -1;
    }

    if ((rx = exanic_acquire_rx_buffer(exanic, 0, 0)) == NULL)
    {
        fprintf(stderr, "%s: %s\n", exanicStr, exanic_get_last_error());
        return -1;
    }

    if ((tx = exanic_acquire_tx_buffer(exanic, 0, 0)) == NULL)
    {
        fprintf(stderr, "%s: %s\n", exanicStr, exanic_get_last_error());
        return -1;
    }

    if (exanic_get_function_id(exanic) != EXANIC_FUNCTION_DEVKIT)
    {
        fprintf(stderr, "%s: %s\n", exanicStr, "Device is not a development kit.");
        return -1;
    }

    if ((application_registers = exanic_get_devkit_registers(exanic)) == NULL)
    {
        fprintf(stderr, "%s: %s\n", exanicStr, exanic_get_last_error());
        return -1;
    }

    reset( application_registers ); //only difference from cpuInit
    signal(SIGINT, sig_handler);

    // generates random data to send, 96 bytes (i.e. output of 32 examples)
    genRnd( tx_buf, sizeof(tx_buf) );

    int delta;
    int idx;
    //while ( keep_running ) {

      for( idx =0; idx<22; idx++){
	
	genRnd(tx_buf, sizeof(tx_buf) );
        sendPkt( tx, tx_buf, sizeof(tx_buf) );

        // wait to return a packet
        while ( size == 0 ){
          size = recvPkt( rx, &( rx_buf ), sizeof(rx_buf) );
        }

        printf("Received pkt of size = %d\n", size );
        printf("Rx1 timestamp = %d\n", application_registers[8]); //timestamp at rx1
        printf("Rx0 timestamp = %d\n", application_registers[7]);
    	printf("inTimer = %d\n", application_registers[9]);
    	printf("outTimer = %d\n", application_registers[10]);        
    	printf("hndshkTimer = %d\n", application_registers[11]);
    	printf("core time = %d\n", application_registers[12]);
        delta += (application_registers[7] - application_registers[8])*6.2;

        printf(" fpga timestamp = %.0lf ns\n", (application_registers[7] - application_registers[8])*6.2 );     
	    printf(" fpga timer = %.0lf ns\n", application_registers[12]*4.0);
        printf(" rx time = %.0lf ns\n", (application_registers[8] - application_registers[9])*6.2 );
	    printf(" tx time = %.0lf ns\n", (application_registers[7]-application_registers[10])*6.2 );
	    printf(" tx hndshk time = %.0lf ns\n\n", (application_registers[7]-application_registers[11])*6.2 );

        sleep(1);
        size = 0;
	    reset( application_registers );
        // continue

      }

      printf("Average time = %d\n", delta/22 );
      

    exanic_release_handle(exanic);
    exanic_release_rx_buffer(rx);
    exanic_release_tx_buffer(tx);
    
    return 0;
    usage_error:
    fprintf(stderr, "Usage: %s <device>\n", argv[0]);
    return -1;
}
