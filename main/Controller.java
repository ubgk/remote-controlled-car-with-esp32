/*COM3505 Internet of Things Second Assignment
Bora Gokbakan
Sy Phan
*/
//Controller Libraries
import com.github.strikerx3.jxinput.*;
import com.github.strikerx3.jxinput.enums.XInputBatteryDeviceType;
import com.github.strikerx3.jxinput.exceptions.XInputNotLoadedException;

//Apache Http Libraries to send an HTTP Request to IFTTT
import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;

//Other libraries...
import com.fazecast.jSerialComm.*;
import java.io.IOException;
import java.util.Arrays; //for buffer manipulation


public class Controller {
    final static String btPortName = "COM6"; //change this for your device, you can check it 
    final static char END_MESSAGE = 'z';
    
    final private static int DEADZONE = 25;

    final static int MAX_SPEED = 200; //0-255, setting this too high can make the robot hard to manouver
    final static float STEERING_MAX_ANGLE = 0.4f; //not actual angles, between 0-1
    
    private static String eventName = "chlYIjduZrJBa04FxZtff0", eventKey = "chlYIjduZrJBa04FxZtff0";
    
    public static void main(String[] args) throws XInputNotLoadedException, InterruptedException, IOException {
        byte[] writeBuffer = null; //byte array to represent the message
        boolean shutDown = false; //boolean value, set to true if exit button is pressed
        
        
        int toggleRegime = 0b0; //we need the current and the previous state of the driving mode toggle button to determine if it is pressed
        boolean tankRegime = false; //false for Arcade driving
        long lastMessageOut = 0; //the time last IFTTT message was sent.
        
        //open the Port
        SerialPort btPort =SerialPort.getCommPort(btPortName);
        btPort.openPort();
        
        //block the program until Serial connection is established
        while(!btPort.isOpen()) {
            System.out.println("Trying to connect to "+btPortName);
            btPort.openPort();

            Thread.sleep(50);
        }
        
        //Setting baud rate
        btPort.setBaudRate(9600);
        System.out.println("Connected, baud rate : " + btPort.getBaudRate());
        
        //Get controllers...
        XInputDevice14[] devices = XInputDevice14.getAllDevices();
        XInputDevice14 controller = devices[0];
        System.out.println("You have " + devices.length + " device(s) connected.");
        
        //Get component set objects 
        XInputComponents comps = controller.getComponents();
        XInputAxes axes = comps.getAxes();
        XInputButtons buttons = comps.getButtons();
        
        //Poll battery level
        XInputBatteryInformation batteryLevel = controller.getBatteryInformation(XInputBatteryDeviceType.GAMEPAD);
        System.out.println("Battery level of your device is "+ batteryLevel.getLevel().toString());
        
        //Main program code
        while(true) {
            //Has ESP32 sent us anything?
            int bytesAvailable = btPort.bytesAvailable();

            //if Serial went down, try reconnecting...
            if(!btPort.isOpen()) {
                btPort.openPort();
                System.out.println("Trying to reconnect...");
                Thread.sleep(50);
            }
            controller.poll();

            Thread.sleep(50);
            
            //if ESP32 is sending back bytes
            if(bytesAvailable>0) {
                int vibration;    //vibration intensity
                
                //we read all values to clear the buffer, but we are only interested in the most recent value.
                byte[] readBuffer = new byte[bytesAvailable];
                int bytesRead = btPort.readBytes(readBuffer, bytesAvailable); //we read all bytes so there is no bytes left in the queue, but process only the first
                System.out.println(bytesRead + " byte(s) were read out of " + bytesAvailable + " byte(s).");
                
                //vibration has a range of 0-65365, so we map our byte accordingly
                if(readBuffer[bytesRead-1]!=0b0) {
                    if(System.currentTimeMillis()-lastMessageOut>15000) {
                        lastMessageOut=System.currentTimeMillis();
                        System.out.println(IFTTT());//send a HTTP Request to IFTTT, print response
                        }
                    vibration = 32365;
                }
                else {
                    vibration = 0;
                    }
                
                System.out.println("Byte read : "+readBuffer[bytesRead-1]);
                
                controller.setVibration(vibration,vibration); //there are two vibration motors inside an Xbox controller
            }
            
            //Do we have commands waiting to be written?
            if(writeBuffer!=null ) {
                System.out.println("Bytes awaiting write: " +btPort.bytesAwaitingWrite() );
                int bytesAwaiting = writeBuffer.length;
                int bytesWritten = btPort.writeBytes(writeBuffer, bytesAwaiting);
                System.out.println(bytesWritten + " byte(s) were written out of " + bytesAwaiting + " byte(s).");
                
                //If we have any leftover bytes that could not be read, we copy them.
                    if(bytesWritten!=bytesAwaiting)
                        writeBuffer = Arrays.copyOfRange(writeBuffer, bytesWritten, bytesAwaiting);
                    else {
                        System.out.println("Null buffer");
                        writeBuffer = null;
                        bytesAwaiting = bytesWritten = 0;
                        
                        //if both the buffer is null (so the kill message was sent) 
                        //and there was a shut down request, we can finally kill the Java program too.
                        if(shutDown)
                            terminate(btPort);
                    }
            }
            
            //If there is no Serial work awaiting, we can read our controller and prepare the new message
            else {
                toggleRegime = (toggleRegime<<1); //we shift the last value to the left
                
                if(buttons.x==true)
                    toggleRegime += 0b1;
                
                //if the previous button state was low and the current is high, toggle modes
                if((toggleRegime & 0b11) == 0b01)
                    tankRegime = !tankRegime;
                
                float brakes = axes.lt; //we read the left trigger (shared by Arcade and Tank Drive modes)
                System.out.print("BRAKES: "+brakes+" ");
                int leftWheel, rightWheel;
                
                
                //TANK REGIME
                if(tankRegime) {
                    leftWheel = (int)(axes.ly * MAX_SPEED * (1.0f-brakes));
                    rightWheel = (int)(axes.ry * MAX_SPEED * (1.0f-brakes));
                    
                }//ARCADE DRIVING REGIME
                else {
                    float steering = axes.rx;
                    steering *= STEERING_MAX_ANGLE;
                    int acceleration = (int)((axes.rt-brakes)*MAX_SPEED);
                    
                    
                    //if steering towards right
                    if(steering>0) {
                        leftWheel = acceleration;
                        rightWheel = (int) (acceleration*(1.0f-steering));
                    }
                    else {//steering towards left
                        leftWheel = (int) (acceleration*(1.0f+steering));;
                        rightWheel = acceleration;
                    }
                        
                }//END OF ARCADE DRIVING REGIME
                
                //Read the shut down button 
                shutDown = buttons.b;
                
                //Create a byte array out of the values read
                writeBuffer = createPackage(sensitivity(leftWheel), sensitivity(rightWheel), buttons.a, shutDown);
            }
        }
    }
    
    //Creates the message that will be sent
    public static byte[] createPackage(int leftWheel, int rightWheel, boolean buzzer, boolean kill) {
        boolean leftInverse = leftWheel<0;
        boolean rightInverse = rightWheel<0;
        
        System.out.print ("LEFT TRACK: " + leftWheel);
        System.out.println(" RIGHT TRACK: " + rightWheel);
        if(leftInverse==rightInverse) {
            if(leftInverse==false) //if both forward
                System.out.println("ADVANCE!");
            else //if both backward
                System.out.println("BACK!");
        }
        leftWheel = Math.abs(leftWheel);
        rightWheel = Math.abs(rightWheel);
        
        /* Preparing the flag byte, first bit from the right represents the left wheels direction,
        second bit represents the right wheel's direction, and third bit represents the buzzer activation, and
        the fourth represents the kill button that puts RoboCar in deep sleep */
        int flagByte = 0b0;
        if(leftInverse)
            flagByte+= 0b1;
        if(rightInverse)
            flagByte+= 0b10;
        if(buzzer)
            flagByte+=0b100;
        if(kill)
            flagByte+=0b1000;
        
        byte [] writeBuffer = new byte[8];


        writeBuffer[0] = (byte)(leftWheel);
        writeBuffer[1] = (byte)(rightWheel);
        writeBuffer[2] = (byte) flagByte;
        
        //Append end bytes, total message size = 6 bytes
        writeBuffer[3] = writeBuffer[4] = writeBuffer[5] = (byte)END_MESSAGE;
        
        return writeBuffer;
    }
    public static void terminate(SerialPort btPort) {
        System.out.println("Terminating");
        btPort.closePort();
        System.exit(0);
    }
    
    //Send a message via IFTTT
    public static String IFTTT() throws IOException, ClientProtocolException {
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost httpPost = new HttpPost("https://maker.ifttt.com/trigger/" + eventName + "/with/key/" + eventKey);
        HttpResponse response = httpClient.execute(httpPost);
        
        return response.toString();
    }
    
    //We send 0 when a stick is at rest
    public static int sensitivity(int value) {
        if(Math.abs(value)<DEADZONE)
            return 0;
        else
            return value;    
    }
}
