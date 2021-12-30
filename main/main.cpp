// COM3505 Internet of Things Assignment 2
// Bora Gokbakan
// Sy Phan

#include <BluetoothSerial.h> //Header File for Serial Bluetooth, will be added by default into Arduino
#include <Adafruit_MotorShield.h>

//Defining Macros
#define dbg(b, s)       if(b) Serial.print(s)
#define dbf(b, ...)     if(b) Serial.printf(__VA_ARGS__)
#define dln(b, s)       if(b) Serial.println(s)
#define dlnF(b, s)      dln(b, F(s)) //using the F macro
#define dbgF(b, s)      dbg(b, F(s)) //using the F macro

#define startupDBG      true
#define loopDBG         false
#define powerDBG      true
#define bufferDBG      true

#define US_SLICE 1000
#define BATTERY_SLICE 100000
#define TIMEOUT 30000 //Go to sleep if no messages were received for 5 minutes
#define DEADZONE 25 //Motor Deadzone PWM
#define AA_THRESHOLD 4.0
#define LIPO_THRESHOLD 3.6 //not used

//Define message rules
#define END_MESSAGE 'z'
#define MESSAGE_LENGTH 6

//GPIO Pins
#define BUZZER_PIN GPIO_NUM_27
#define WAKE_PIN GPIO_NUM_33
#define BT_PIN GPIO_NUM_12
#define TRIG_PIN GPIO_NUM_32
#define ECHO_PIN GPIO_NUM_14

//ADC Pins
#define LIPO_IN A1 //Lipo voltage divider input
#define AA_IN A5 //AA voltage divider input

#define LEFT_MASK 0b1
#define RIGHT_MASK 0b10
#define BUZZER_MASK 0b100
#define KILL_MASK 0b1000

// declaring function protos
void printBuffer();
void bufferPop();
void changeState();
uint8_t obstacle_detect();
bool sleep_conditions();
void deep_sleep();

//Voltage Conversion Constants
const float ADC_2_VOLT = 3.30/4095.0; //12 bit to 3.3v mapping
const float LIPO_CONV = 0.75; //LiPo voltage divider gives 3/4 of Vin to ADC
const float AA_CONV = 0.50; //AA voltage divider gives half of Vin to ADC

BluetoothSerial ESP_BT; //Object for Bluetooth

// Create the motor shield object with the default I2C address
Adafruit_MotorShield AFMS = Adafruit_MotorShield(); 
 
// And connect 2 DC motors to port M3 & M4 !
Adafruit_DCMotor *L_MOTOR = AFMS.getMotor(4);
Adafruit_DCMotor *R_MOTOR = AFMS.getMotor(3);

//Variables for reading from serial
uint8_t buffer[] = {0,0,0,0,0,0,0,0}; //All zeros buffer
uint8_t incomingByte = -1; //Store a single byte
bool valid = false; //Initial buffer is not a valid one
bool waiting = false; //if the loop function is waiting for a new byte
bool bt_active = false;

unsigned long lastMessage = 0;
float aa_volt, lipo_volt;
uint8_t loopCount;

void callback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param){
  if(event == ESP_SPP_SRV_OPEN_EVT){
    digitalWrite(BT_PIN,HIGH);
    bt_active = true;
    dlnF(startupDBG,"We have a client connected");
  }

  if(event == ESP_SPP_CLOSE_EVT ){
    digitalWrite(BT_PIN,LOW);
    bt_active = false;
    dlnF(startupDBG,"No clients connected");
  }
}

void setup() {
        AFMS.begin();
        pinMode(BUZZER_PIN, OUTPUT); //Buzzer pin
        pinMode(BT_PIN, OUTPUT); //Blue LED, shows BT State
        pinMode(TRIG_PIN, OUTPUT); //Ultrasonic sensor pins
        pinMode(ECHO_PIN, INPUT_PULLDOWN); //echo pin pulled down
        
        Serial.begin(9600);
        ESP_BT.begin(F("COM3505 BT Car 10/2"));
        dbgF(startupDBG,"We terminate at letter ");
        Serial.print(END_MESSAGE);
        dbgF(startupDBG," with byte value of ");
        Serial.println(END_MESSAGE, DEC);
        dlnF(startupDBG,"-----------");
        
}
//main program loop  
void loop() {
        //if sleep conditions are met, go to sleep
        if(sleep_conditions())
          deep_sleep();
          
        //check if the buffer holds a valid and complete message (i.e. the last 3 bytes equal end_byte)
        valid = isValid(buffer, END_MESSAGE);

        //if the buffer holds a valid message
        if(valid){
          changeState(); //change RoboCar state according to the message
          if(ESP_BT.readBytes(buffer, MESSAGE_LENGTH)!= 0) //read the new message
            lastMessage = millis();
        }
        //if the buffer does not hold a valid message
        else{
          //don't pop over and over every time the loop runs
          if(!waiting)
            bufferPop(); //slide the buffer to the left

          //try to read a byte
          incomingByte = ESP_BT.read();

          //if a byte is read, place it at the end of the buffer
          if(incomingByte!= -1){
            buffer[MESSAGE_LENGTH-1] = incomingByte;
            waiting = false; 
            }
          //we keep waiting for a new byte without blocking the code  
          else
            waiting = true;
        }
      //every 1000th iteration, update the controller
      if(loopCount%US_SLICE==0)
        ESP_BT.write(obstacle_detect());
      //read battery status
      if(loopCount%BATTERY_SLICE==0){
        aa_volt = analogRead(AA_IN)*ADC_2_VOLT/AA_CONV;
        lipo_volt = analogRead(LIPO_IN)*ADC_2_VOLT/LIPO_CONV;

        String s = F("AA Volts: ");
        s.concat(aa_volt);
        s.concat(F(" LiPo Volts: "));
        s.concat(lipo_volt);
        dln(powerDBG, s);
        ESP_BT.println(s);
        
      //drive the Blue LED high if we have a client
        if(bt_active){
          dlnF(loopDBG,"We have a client.");
          digitalWrite(BT_PIN, HIGH );
          }else{
            dlnF(loopDBG,"We don't have a client.");
          digitalWrite(BT_PIN, LOW );
            }
        
        
      }
      //increase the loopcount
      loopCount++;
}

//check if the buffer in memory is a valid message
bool isValid(uint8_t buffer[], uint8_t end_byte){
    int i = MESSAGE_LENGTH-3;
    
   //if ith element is equal to the end_byte and  the following two are equal
   if( (buffer[i] == end_byte) && (allSame(buffer[i],buffer[i+1],buffer[i+2])))
    return true;
    else
    return false; 
  }

//Debug Function
void printBuffer(){
  if(!loopDBG)
    return;
  dlnF(startupDBG,"-----------");
  dlnF(startupDBG,"Bytes received:");
  for(uint8_t one:buffer){
    Serial.print((char)one);
  }
  Serial.println();
  }

//Pops one byte from the head of the buffer to discard the corrupted previous message
void bufferPop(){
  //Serial.print(F("Corrupted message, skipping : "));
  //printbuffer();
  for(int i=0; i<(MESSAGE_LENGTH-1); i++){
    buffer[i]=buffer[i+1];
    }
    buffer[MESSAGE_LENGTH-1]=0;
  }

//Function to check if given 3 bytes are the end of a message  
bool allSame(uint8_t val1, uint8_t val2, uint8_t val3){
  if((val1 == val2) && (val2 == val3))
    return true;
    else
    return false;
  }      

//State transition function
void changeState(){
  printBuffer();
  int lpwm  = buffer[0];
  int rpwm  = buffer[1];
  int flagByte  = buffer[2];  
  
  //adjusting the PWMs
  L_MOTOR->setSpeed(deadzone(lpwm));
  R_MOTOR->setSpeed(deadzone(rpwm));

  //if left motor is in forward
  if((flagByte&LEFT_MASK)==0)
    L_MOTOR->run(FORWARD);
    else
    L_MOTOR->run(BACKWARD);

  //if right motor is in forward
  if((flagByte&RIGHT_MASK)==0)
    R_MOTOR->run(FORWARD);
    else
    R_MOTOR->run(BACKWARD);

  //if "honking"
  if((flagByte&BUZZER_MASK)!=0)
    digitalWrite(BUZZER_PIN, HIGH);
    else
    digitalWrite(BUZZER_PIN, LOW);
  
  //if kill button is pressed
  if((flagByte&KILL_MASK)>0){
    dlnF(startupDBG,"Kill button pressed");
    deep_sleep(); 
    }
    

  }

//we poll the Ultrasonic sensor, return a value of 0-255cm distance
uint8_t obstacle_detect(){
   long distance = 0;
   digitalWrite(TRIG_PIN, LOW);
   delayMicroseconds(5);
   digitalWrite(TRIG_PIN, HIGH);
   delayMicroseconds(10);
   digitalWrite(TRIG_PIN, LOW);
   distance = pulseIn(ECHO_PIN, HIGH)*0.017;

   if(distance<20){
    digitalWrite(BT_PIN, HIGH);
    dlnF(startupDBG,"OBSTACLE PRESENT");
    return 0b1;
    }
   else{
    dlnF(startupDBG,"OBSTACLE ABSENT");
    digitalWrite(BT_PIN,LOW);
    return 0b0;}
}

//Motor does not produce enough torque below 25 PWM, but only coil whine, so we ignore values below threshold  
int deadzone(int value){
  if(value<DEADZONE)
    return 0;
  else
    return value;
}

//Check if sleep conditions are met
bool sleep_conditions(){
   if (millis()-lastMessage>TIMEOUT){
    dlnF(powerDBG,"No messages being received, going to sleep!");
    return true;
    }
    else if(aa_volt<AA_THRESHOLD){
      dlnF(powerDBG,"AA Batteries are dying out!");
      return false;
      }
    else
      return false;      
    }

//Go to sleep
void deep_sleep(){
    dlnF(startupDBG,"We are going to deep sleep");
    L_MOTOR->run(RELEASE);
    R_MOTOR->run(RELEASE);
    ESP_BT.flush();
    ESP_BT.end(); //ending BT

    //we go to sleep, we wake up if pin 33 is driven HIGH. This pin should be pulled down.
    esp_sleep_enable_ext0_wakeup(GPIO_NUM_33,1);
    esp_deep_sleep_start();
  }

