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


int keep_running = 1;

struct Example {
  char forceNA;
  double * exs;
};

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

void printErr( volatile uint32_t * application_registers ) {
  uint32_t err;
  if ( (err = application_registers[REG_ERR]) != 0 ) {
    application_registers[REG_CNTRL] = 1;
    // fifoTxOut.io.count ## segmentCounter ## error
    // error = rx1Err ## pktDrop ## crcFail ## fifoFull ## txFifoFull ## dirOutFull ## userErr
    printf("fifoCount = %d\n", err >> 11);
    printf("segmentCounter = %d\n", (err >> 7) & 15);
    printf("rx1Err = %d\n", (err >> 6) & 1);
    printf("pktDrop = %d\n", (err >> 5) & 1);
    printf("crcFail = %d\n", (err >> 4) & 1);
    printf("fifoFull = %d\n", (err >> 3) & 1);
    printf("txFifoFull = %d\n", (err >> 2) & 1);
    printf("dirOutFull = %d\n", (err >> 1) & 1);
    printf("userErr = %d\n", err & 1 );
    if ( err & 1 ) {
      err = application_registers[0];
      printf("dataOut.ready = %d\n", (err >> 2) & 1 );
      printf("asyncFifo.ready = %d\n", (err >> 1) & 1 );
      printf("outAsync.ready = %d\n", err & 1 );
      err = application_registers[1];
      printf("asyncFifo.count = %d\n", err );
      err = application_registers[2];
      printf("outAsync.count = %d\n", err );
    }
    int idx;
    for ( idx = 0; idx < 8; idx++ ) {
      err = application_registers[3 + idx];
      printf("dataOut(%i) = %x\n", idx, err );
    }
  }

  application_registers[REG_CNTRL] = 0;
}

int toFixed( double x ) {
  return (int)(x*( 1 << FRACWIDTH ));
}

int littleToBigEndian( int x ) {
  int tmp = 0;
  char * tmpC = &tmp;
  char * xC = ((char *) &x );
  int i;
  for ( i = 0; i < 4; ++i )
    tmpC[i] = xC[3 - i];
  return tmp;
}

/*int toFixed( int fracWidth, float x ) {
  return (int)(x*( 1 << fracWidth ));
  }*/

double fromFixed( int x ) {
  // sign extend
  if ( x & ( 1 << 23 ) )
    x |= 0xff << 24;
  return ( (double)(x) )/( 1 << FRACWIDTH );
}

void parseLine( char * line, struct Example * data, int noEx ) {
  size_t lineLen = strlen(line);
  int pos = 0;
  int fieldStart = 0;
  int fieldNo = 0;
  char * endPtr = NULL;
  data->exs = (double*) malloc( sizeof(double)*noEx );
  while ( pos < lineLen ) {
    if ( line[pos] == ',' ) {
      switch ( fieldNo ) {
      case 0: // currently redundant first field
	break;
      case 1: // read if is test or not
	data->forceNA = !strncmp( "True", &line[fieldStart], 4 );
	break;
      case 2: // the class, not needed for anomaly detection
	break;
      default:
	endPtr = &line[pos-1];
	data->exs[fieldNo - 3] = strtod( &line[fieldStart], &endPtr );
      }
      fieldNo++;
      fieldStart = pos+1;
    }
    pos++;
  }
  endPtr = &line[pos-1];
  data->exs[fieldNo - 3] = strtod( &line[fieldStart], &endPtr );
}

int parseFile( FILE * f, struct Example ** exs, int exLen ) {
  int bufSize = 4096;
  char * buf = (char*) malloc( sizeof(char) * bufSize );
  char ** bufPtr = &buf;
  fseek(f, 0L, SEEK_END);
  int fileSize = ftell(f);
  rewind(f);
  int fileIdx = 0;
  int exIdx = 0;
  
  // find number of examples
  int nChar = getline( bufPtr, &bufSize, f );
  int noEx = -2;
  int i;
  for( i = 0; i < nChar; i++ )
    noEx += ( buf[i] == ',' );

  printf( "%d examples detected, fileSize = %d\n", noEx, fileSize);
  rewind( f );
  
  // loop through lines creating the examples
  while ( fileIdx < fileSize && exIdx < exLen ) {
    nChar = getline( bufPtr, &bufSize, f );
    if ( nChar <= 0 )
      break;
    struct Example * data = (struct Example *) malloc( sizeof( struct Example ) );
    parseLine( buf, data, noEx );
    exs[exIdx] = data;
    fileIdx += nChar;
    exIdx++;
  }
  free( buf );
  return exIdx;
}

// number of bytes in fixed
#define NOBYTES 3
// convert doubles to fixed point to send to exanic card
// uses 8.16 fixed point format
int exsToPkt( char * buf, int bufLen, struct Example ** exs, int exsLen,
	      int * exsIdx, int * ftrIdx, int noEx ) {
  int bufIdx = 0;
  while ( *exsIdx < exsLen && bufIdx < bufLen ) {
    while ( *ftrIdx < NOBYTES*noEx ) {
      int fixedVal = toFixed( exs[*exsIdx]->exs[*ftrIdx / NOBYTES] );
      if ( bufLen - bufIdx <= 0 )
	break;
      int i;
      for ( i = 0; i < NOBYTES; i++ ) {
	// check that space left and haven't already put in prev packet
	if ( bufLen - bufIdx > 0 && ( *ftrIdx % NOBYTES ) <= i ) {
	  buf[bufIdx] = (fixedVal >> i*8) & 0xff;
	  (*ftrIdx) += 1;
	  bufIdx++;
	}
      }
    }
    //    printf( "exsIdx = %d, ftrIdx = %d\n", *exsIdx, *ftrIdx );
    if ( *ftrIdx == NOBYTES*noEx ) {
      *ftrIdx = 0;
      *exsIdx = *exsIdx + 1;
    }
  }
  return bufIdx;
}

void writeToFile( FILE * f, char * buf, int bufLen ) {
  int bufIdx = 0;
  char floatBuf[64];
  while ( bufIdx < bufLen ) {
    int fixedBits = 0;
    int i = 0;
    for ( i = 0; i < NOBYTES; i++ ) {
      fixedBits += ( ((unsigned int) buf[bufIdx]) & 0xff ) << ( 8*i );
      printf("buf[%d] = %x\n", i, buf[bufIdx]);      
      bufIdx++;
    }
    printf("fixedBits = 0x%x\n", fixedBits);
    int size = sprintf(floatBuf, "false,1,%f\n", bufIdx/NOBYTES, fromFixed( fixedBits ) );
    fwrite( floatBuf, sizeof(char), size, f );
  }
}

int main(int argc, char *argv[])
{
    exanic_t *exanic;
    exanic_rx_t *rx;
    exanic_tx_t *tx;
    volatile uint32_t *application_registers;
    char rx_buf[2048];
    char tx_buf[60];
    int size = 0;
    int rxIdx = 0;
    char * exanicStr = "exanic0";
    char * fileName = argv[1];
    char fileNameBuf[1024];
    FILE * fd_R = NULL;
    FILE * fd_W = NULL;
    
    if (argc != 2)
        goto usage_error;

    sprintf(fileNameBuf, "%s.csv", fileName);
    if ( ( fd_R = fopen( fileNameBuf, "r" ) ) == NULL )
    {
      fprintf(stderr, "%s: could not open file %s\n", exanicStr, fileNameBuf );
        return -1;
    }
    
    sprintf(fileNameBuf, "%s-out.csv", fileName);
    if ( ( fd_W = fopen( fileNameBuf, "w" ) ) == NULL )
    {
      fprintf(stderr, "%s: could not open file %s\n", exanicStr, fileNameBuf );
        return -1;
    }
    
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

    int noEx = 2000;
    struct Example ** exs = (struct Example **) malloc( noEx*sizeof(struct Example *) );
    noEx = parseFile( fd_R, exs, noEx );
    int exsIdx = 0;
    int ftrIdx = 0;
    int exLen = 0;
    while ( exLen < noEx ) {
      if ( exs[exLen]->forceNA )
	 break;
      exLen++;
    }
    printf("file parsed, exLen = %d, noEx = %d\n", exLen, noEx);
    
    reset( application_registers );
    application_registers[0] = toFixed( 0.0506246281646581 );
    application_registers[1] = toFixed( 0.95 );
    application_registers[2] = toFixed( 0.05 );
    application_registers[3] = toFixed( 0.1 );
    application_registers[4] = 0;

    printErr( application_registers );
    
    signal(SIGINT, sig_handler);

    int idx = 0;
    while ( keep_running ) {
      int sendBytes = exsToPkt( tx_buf, sizeof(tx_buf), exs, exLen, &exsIdx, &ftrIdx, 8);
      if ( sendBytes == 0 ) {
	application_registers[4] = 1;
	sendBytes = exsToPkt( tx_buf, sizeof(tx_buf), exs, noEx, &exsIdx, &ftrIdx, 8);
	if ( sendBytes > 0 )
	  printf("set forceNA\n");
      }
      if ( sendBytes > 0 ) {
	printf("sending %d bytes\n", sendBytes );
	sendPkt( tx, tx_buf, sendBytes );
      }

      size = recvPkt( rx, &( rx_buf[rxIdx] ), sizeof(rx_buf) - rxIdx );
      if ( size > 0 ) {
	printf( "Recieved pkt of size = %d\n", size );
	// check divisible by NOBYTES
	int total = size + rxIdx - 4;
	int trimmed = NOBYTES*( total/NOBYTES );
	printf( "trimmed = %d\n", trimmed );
	writeToFile( fd_W, rx_buf, trimmed );
	for( rxIdx = 0; rxIdx + trimmed < total; rxIdx++ )
	  rx_buf[rxIdx] = rx_buf[trimmed + rxIdx];
      }
      if ( idx < 22 ) {
	printErr( application_registers );
	idx++;
      }
    }

    for ( idx = 0; idx < noEx; idx++ ) {
      free( exs[idx]->exs );
      free( exs[idx] );
    }
    free( exs );
    
    exanic_release_handle(exanic);
    exanic_release_rx_buffer(rx);
    exanic_release_tx_buffer(tx);
    fclose( fd_W );
    fclose( fd_R );
    
    return 0;
    usage_error:
    fprintf(stderr, "Usage: %s <device>\n", argv[0]);
    return -1;
}
