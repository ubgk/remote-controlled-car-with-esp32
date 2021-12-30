import java.lang.Math;

import com.github.strikerx3.jxinput.*;
//NOTE: Not included in the final product. Works but increases control lag greatly.
//TankDrive class for differential steering robots. Supports both Tank-style driving and Ackermann steering geometry simulation.
public class TankDrive {
    private double wheelBase; //distance between front and rear axles

    private double axleWidth; //width of the axle

    private double maxAngle; //in rads
    
    private int maxSpeed;

    private double maxLineSpeed;
    
    private double rearRadius, frontRadius, centerRadius, innerCenter, outerCenter;
    
    public enum Regime {TANK, ARCADE};
    
    private Regime regime;
    
    private XInputDevice14 controller;
    private XInputComponents comps;
    private XInputAxes axes;
    private XInputButtons buttons;
    
    public static void print(String s) {
        System.out.print(s);
    }
    public static void println(String s) {
        System.out.println(s);
    }
    public TankDrive(double wb, double aw, double mangl, int mxSpd, TankDrive.Regime reg, XInputDevice14 cntrl) {
        this.wheelBase = wb; //wheel base
        this.axleWidth = aw; //axle
        this.maxAngle = mangl; //max steering angle
        this.maxSpeed = mxSpd; //max Speed
        this.regime = reg; //steering regime
        this.controller = cntrl;
        this.comps = controller.getComponents();
        this.axes = comps.getAxes();
        this.buttons = comps.getButtons();
        update();
    }
    
    public TankDrive(double wb, double aw, double mangl, int mxSpd, TankDrive.Regime reg) {
        //initiating fields
        this.wheelBase = wb; //wheel base
        this.axleWidth = aw; //axle
        this.maxAngle = mangl; //max steering angle
        this.maxSpeed = mxSpd; //max Speed
        this.regime = reg; //steering regime
        
        update();
    }
    

    private double stickToAngle(double stick) {
        return stick*Math.toDegrees(maxAngle);
    }
    private double steeringAngle(double stick) {
        return Math.asin(stick)*maxAngle;
    }
    //getter and setter methods below
    private void update() {
        //determining the turn radiuses when turning at the maxAngle
        this.rearRadius = wheelBase / Math.tan(maxAngle);
        this.frontRadius = Math.sqrt(wheelBase*wheelBase + rearRadius*rearRadius);
        this.centerRadius =  Math.sqrt(((wheelBase / 2) * (wheelBase / 2)) + (rearRadius*rearRadius));
        
        //determining the track radiuses turning at max speed at max angle
        this.innerCenter = centerRadius - (axleWidth/2);
        this.outerCenter = centerRadius + (axleWidth/2);
        
        //determining the base speed
        this.maxLineSpeed = maxSpeed * centerRadius/outerCenter;
    }    
    
    public int[] speedsWrapper() {
        if(this.regime==Regime.ARCADE) 
            return getSpeedsArcade(axes.lx,axes.rt-axes.lt);
        else 
            return getSpeedsTank(axes.ly,axes.ry);
        
    }
    public int[] getSpeedsTank(float leftAxis, float rightAxis) {
        leftAxis *= maxSpeed;
        rightAxis *= maxSpeed;
        
        return new int[] {(int) leftAxis, (int) rightAxis};
    }
    public int[] getSpeedsArcade(float steeringAxis, float throttle) {
        //System.out.println("--Debug--");
        boolean equal = (steeringAxis == 0);
        boolean turningLeft = steeringAxis<0;
        boolean forward = throttle>0;
        
        if(Math.abs(steeringAxis)>1)
            throw new ArithmeticException("Steering Axis should be between -1 and 1!");
        else if(Math.abs(throttle)>1)
            throw new ArithmeticException("Throttle Axis should be between -1 and 1!");
        
        double angle = Math.abs(steeringAxis);
        //angle = Math.toRadians(stickToAngle(angle));
        angle = steeringAngle(angle);
        
        double baseSpeed = throttle*maxLineSpeed;
        
        if(equal) {
            System.out.println("FULL ADVANCE");
            return new int[] {(int) baseSpeed, (int) baseSpeed};
            }
            
        //determining the turn radiuses when turning at the steeringAxis angle
        double rearRadius = wheelBase / Math.tan(angle);
        System.out.println("Rear radius : " + rearRadius);
        double centerRadius =  Math.sqrt(((wheelBase / 2) * (wheelBase / 2)) + (rearRadius*rearRadius));

        System.out.println("Center radius : " + centerRadius);
        
        //determining the track speeds turning at max speed at max angle
        double innerSpeed = (centerRadius - (axleWidth/2)) * baseSpeed / centerRadius;
        double outerSpeed = (centerRadius + (axleWidth/2)) * baseSpeed / centerRadius;
        
        float leftSpeed, rightSpeed;
        
        if(turningLeft) {
            leftSpeed = (float) innerSpeed;
            rightSpeed = (float) outerSpeed;
        }
        else {
            leftSpeed =  (float) outerSpeed;
            rightSpeed =  (float) innerSpeed;
        }
        
        return new int[] {(int)leftSpeed, (int)rightSpeed};
    }
    public double getWheelBase() {
        return wheelBase;
    }
    public double getAxleWidth() {
        return axleWidth;
    }
    public int maxSpeed() {
        return maxSpeed;
    }
    public double getMaxAngle() {
        return maxAngle;
    }
    public double getMaxAngleDegrees() {
        return Math.toDegrees(maxAngle);
    }
    public Regime getRegime() {
        return regime;
    }

    public void setWheelBase(double wb) {
        this.wheelBase = wb;
        update();
    }
    public void setAxleWidth(double aw) {
        this.axleWidth = aw;
        update();
    }
    public void setMaxSpeed(int mxSpd) {
        this.maxSpeed = mxSpd;
        update();
    }
    public void setMaxAngle(double mangl) {
        this.maxAngle = mangl;
        update();
    }
    public void setMaxAngleDegrees(double mangl) {
        this.maxAngle = Math.toRadians(mangl);
        update();
    }
    public void setRegime(Regime reg) {
        this.regime = reg;
    }
    public void toggleRegime() {
        if(this.regime == Regime.ARCADE)
            this.regime = Regime.TANK;
        else
            this.regime = Regime.ARCADE;
    }
}
