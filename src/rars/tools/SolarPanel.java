package rars.tools;

import rars.Globals;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.AddressErrorException;
import rars.util.Binary;

import java.util.Observable;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;


/**
 * Solar panel with MMIO.
 * @author Pablo Muñoz (UAH)
 */
public class SolarPanel extends AbstractToolAndApplication {

    private static final String TOOLNAME = "Solar Panel";
    private static final String VERSION = "1.0";
    
    /** Memory addreses for the MMIO */
    static final int MEM_IO_WRITE_POWER   = Memory.memoryMapBaseAddress + 0x00;
    static final int MEM_IO_WRITE_SENSORS = Memory.memoryMapBaseAddress + 0x20;
    static final int MEM_IO_WRITE_ANGLE   = Memory.memoryMapBaseAddress + 0x40;
    static final int MEM_IO_READ_COMMAND  = Memory.memoryMapBaseAddress + 0x60;
    static final int MEM_IO_WRITE_BATTERY = Memory.memoryMapBaseAddress + 0x80;
    static final int MEM_IO_STATUS        = Memory.memoryMapBaseAddress + 0xe0;
  
    /** Simulation speed factor for time computations (>0) */
    static int SPEED_FACTOR = 1;
    /** Battery thread frequency in milliseconds */
    static final int BATTERY_FREQUENCY = 250;
    /** Sensor max value (sensor in [0..MAX_S_VAL] */
    static final int MAX_SENSOR_VALUE = 255;
    /** Test time in seconds */
    static int TEST_DURATION = 60;
    /** Number of cycles to repeat the test */
    static int TEST_CYCLES = 2;
    /** Motor speed in milli-degrees per second */
    static int MOTOR_SPEED = 7000;
    /** Solar panel maximum out power in mW */
    static int MAX_OUTPUT_POWER = 900000;
    /** Battery capacity in mAh */
    static final int MAX_BATTERY_CAPACITY = 42000;
    /** Initial battery level in mAh */
    static final int INITIAL_BATTERY_PCT = 10;
    /** Power bus voltage in mV */
    static final int BUS_VOLTAGE = 32000;
    /** Minimum solar panel angle in milli-degrees */
    static final int MIN_PANEL_ANGLE = -30000;
    /** Maximum solar panel angle in milli-degrees */
    static final int MAX_PANEL_ANGLE = 30000;
    /** Limit values of the slider that implies no Sun coverage */
    static final int SUN_SHADE_SLIDER_LIMITS = 50;
    /** Thickness of the displayed solar panel */
    static final int PANEL_STROKE = 5;
    /** The default configuration string */
    static final String DEFAULT_CONFIG = "060270009000";
    /** Current battery capacity in mW */
    double batteryLevel = 0;
    /** Current output power from solar panel in mW */
    double outputPower = 0;
    /** Current solar panel angle in milli-degrees, between MIN_PANEL_ANGLE and MAX_PANEL_ANGLE */
    int panelAngle = 0;
    /** Relationship between slider ticks and sun angle */
    double sliderToDegrees = 1;
    /** Current Sun position, based on slider. Position is in degrees can be computed through {@link #getSunDegrees()} */
    int sunPos = 0;
    /** Indicates if the motor is moving */
    boolean motorIsMoving;
    /** Status flag */
    int status = 0;
    /** Failures counter */
    static int failures = 0;
    /** Battery thread */
    BatteryThread battery; 
    /** Motor movement thread (single run per movement) */
    MotorMovement motor;
    /** Automatic Sun movment for test (single run per TEST) */
    SunTest test;
    /** The coords for the line representing the solar panel: (x,y,length) */
    int[] solarPanelLine;
    /** Required to avoid GUI initialization on reset behaviour */
    private boolean notInitialized = true;
    
    /**
     * Constructor. Set the tool name and version
     */
    public SolarPanel() {
        super(SolarPanel.TOOLNAME + ", " + SolarPanel.VERSION, SolarPanel.TOOLNAME);
    }
    public SolarPanel(String title, String heading) {
        super(title, heading);
    }
    
    /**
     * @return the tool name
     */
    @Override
    public String getName() {
        return SolarPanel.TOOLNAME;
    }
    
    /**
     * Initialize all components. 
     * @return the builded component
     */
    @Override
    protected JComponent buildMainDisplayArea() {
        if(notInitialized)
            initComponents();
        notInitialized = false;
        outputPower = 0;
        panelAngle = 0;
        status = 0;
        failures = 0;
        // The solar panel line components (x,y,length)
        int lineWidth = canvas.getWidth()/8;
        solarPanelLine = new int[]{canvas.getWidth()/2,
            canvas.getHeight()-(int)(lineWidth*Math.sin(Math.toRadians(MAX_PANEL_ANGLE/1000))),
            lineWidth};
        setBatteryLevel(INITIAL_BATTERY_PCT);
        angleValueLabel.setText("0º");
        // Set correspondence between slider ticks and degrees
        int maxs = sunSlider.getMaximum();
        sliderToDegrees =  180.0 / (maxs-2*SUN_SHADE_SLIDER_LIMITS);
        sunSlider.setValue(maxs/4);
        // Create and start threads for battery and sensors
        battery = new BatteryThread();
        battery.start();
        return panelTools;
    }
    
    /**
     * Finish all operations
     */
    @Override
    protected void performSpecialClosingDuties() {
        battery.finish();
        if(motor != null)
            motor.interrupt();
        if(test != null)
            test.interrupt();
        try {
            Thread.sleep(BATTERY_FREQUENCY);
            battery.join(BATTERY_FREQUENCY);
        } catch (InterruptedException ex) {
            System.out.println("Close failure: "+ex.toString());
        }
    }
    
    /**
     * Finish all threads and initializes data
     */
    @Override
    protected void reset() {
        performSpecialClosingDuties();
        buildMainDisplayArea();
        // Reset config as well
        setConfigFromString(DEFAULT_CONFIG);
    }
    
    @Override
    protected void addAsObserver() {
        addAsObserver(MEM_IO_READ_COMMAND, MEM_IO_READ_COMMAND);
    }  
    
    /**
     * A help popup window on how to use this tool (provided by the simulator UI)
     */
    @Override
    protected JComponent getHelpComponent() {
        // TODO UPDATE HELP INFO
        final String helpContent =
            "Use this tool to simulate the Memory Mapped IO (MMIO) for controlling a solar panel.\n"+
            "\nMMIO addresses:\n" +
            Binary.intToHexString(MEM_IO_WRITE_POWER)+" > Output power of the solar panel (mWh) (Nominal power: "+MAX_OUTPUT_POWER/1000+"Wh)\n"+
            Binary.intToHexString(MEM_IO_WRITE_SENSORS)+" > Sensors measurement [0,"+MAX_SENSOR_VALUE+"]. 16bits left sensor | 16bits right sensor\n"+ 
            Binary.intToHexString(MEM_IO_WRITE_ANGLE)+" > Solar panel angle position in ["+MIN_PANEL_ANGLE+","+MAX_PANEL_ANGLE+"] millidegrees\n"+
            Binary.intToHexString(MEM_IO_READ_COMMAND)+" < Next panel position in millidegrees. Motor speed is "+String.format("%.3f",MOTOR_SPEED/1000.0)+"º/s\n"+
            Binary.intToHexString(MEM_IO_WRITE_BATTERY)+" > Current battery level (mAh) (Max battery capacity: "+MAX_BATTERY_CAPACITY/1000+"Ah)\n"+
            Binary.intToHexString(MEM_IO_STATUS)+" > Status flag: b0 = motor moving (1) stopped (0) | b1 = executing test (1) idle (0).\n"+
            "\nAll values are expressed in C2."    
        ;
        JButton help = new JButton("Help");
        help.addActionListener(
            new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    JTextArea ja = new JTextArea(helpContent);
                    ja.setRows(20);
                    ja.setColumns(60);
                    ja.setLineWrap(true);
                    ja.setWrapStyleWord(true);
                    JOptionPane.showMessageDialog(null, new JScrollPane(ja),
                            "Simulation of solar panel control", JOptionPane.INFORMATION_MESSAGE);
                }
            });
        return help;
    }
    
    /**
     * Read MMIO updates. Only motor movement commands are accepted
     * @param resource
     * @param notice 
     */
    @Override
    protected void processRISCVUpdate(Observable resource, AccessNotice notice) {       
        if (!notice.accessIsFromRISCV())
            return;
        // check for a read access in the text segment
        if (notice.getAccessType() == AccessNotice.WRITE && notice instanceof MemoryAccessNotice) {
            // now it is safe to make a cast of the notice
            MemoryAccessNotice memAccNotice = (MemoryAccessNotice) notice;
            int a = memAccNotice.getAddress();
            if (a != MEM_IO_READ_COMMAND)
                return;
            int command = memAccNotice.getValue();
            // No commands are accepted while motor is running
            if(!motorIsMoving && command >= MIN_PANEL_ANGLE && command <= MAX_PANEL_ANGLE)
            {
                motorIsMoving = true;
                motor = new MotorMovement(command);
                motor.start();
            }
            else
                failures++;
        }
    }
    
    /**
     * Writes a word to a virtual memory address
     * @param dataAddr
     * @param dataValue 
     */
    private static synchronized void updateMMIOControlAndData(int dataAddr, int dataValue) {
        Globals.memoryAndRegistersLock.lock();
        try {
                Globals.memory.setRawWord(dataAddr, dataValue);
            } catch (AddressErrorException aee) {
                System.out.println("Tool author specified incorrect MMIO address!" + aee);
        } finally {
            Globals.memoryAndRegistersLock.unlock();
        }
    }

    /**
     * Thread to simulate solar panel output power and battery charge. 
     * It also provides updates on the solar intensity sensors
     */
    private class BatteryThread extends Thread {
        private boolean finish = false;
        private double newBatteryLevel = -1;
        
        public BatteryThread() {
        }
        
        private void finish() {
            finish = true;
        }
        
        /**
         * Overrides current battery level with a new one
         * @param batteryLevel the desired battery level in Ah
         */
        public void setBattery(double batteryLevel)
        {
            if(batteryLevel < 0 || batteryLevel > MAX_BATTERY_CAPACITY/1000)
                return;
            newBatteryLevel = batteryLevel;
        }
        
        @Override
        public void run() {
            long delay;
            double incidence;
            double charge;
            int lval;
            int rval;
            double logf;
            double ll = 2;
            double cf = MAX_SENSOR_VALUE / ll;
            do{
                try {
                    delay = System.currentTimeMillis();
                    incidence = getIncidenceAngle();
                    if(newBatteryLevel < 0)
                    {
                        // Compute solar panel output power
                        if(incidence<=0 || incidence >= 180)
                            outputPower = 0;
                        else
                            outputPower = (int)(Math.sin(Math.toRadians(incidence))*MAX_OUTPUT_POWER);
                        // Increase battery level, charge is mAh
                        charge = outputPower / BUS_VOLTAGE; 
                        charge = charge*BATTERY_FREQUENCY/3600000*SPEED_FACTOR;
                        batteryLevel += charge*1000; // Battery in mAh
                        if(batteryLevel > MAX_BATTERY_CAPACITY)
                            batteryLevel = MAX_BATTERY_CAPACITY;
                    }
                    else // Overrides battery level
                    {
                        batteryLevel = newBatteryLevel*1000;
                        newBatteryLevel = -1;
                    }
                    // UPDATE MMIO for output power in mWh and battery in mAh
                    updateMMIOControlAndData(MEM_IO_WRITE_POWER, (int)outputPower);
                    updateMMIOControlAndData(MEM_IO_WRITE_BATTERY, (int)batteryLevel);
                    // Update GUI labels
                    poutValueLabel.setText(String.format("%.3f Wh", outputPower/1000)); //Wh
                    batteryValueLabel.setText(String.format("%.3f Ah (%.2f%%)", batteryLevel/1000, batteryLevel/MAX_BATTERY_CAPACITY*100)); //Ah (%)
                    
                    // Sensors update                   
                    if(isDark())
                    {
                        lval = 0;
                        rval = 0;
                    }
                    else
                        if(incidence < 90)
                        {
                            logf = Math.log(incidence/90.0)+ll;                            
                            lval = MAX_SENSOR_VALUE;
                            rval = (int)(logf*cf);
                            rval = rval<0? 0:rval;
                        }
                        else
                        {
                            logf = Math.log((180-incidence)/90.0)+ll;
                            lval = (int)(logf*cf);
                            lval = lval<0? 0:lval;
                            rval = MAX_SENSOR_VALUE;
                        }
                    // Write GUI labels
                    lintValueLabel.setText(String.valueOf(lval));
                    rintValueLabel.setText(String.valueOf(rval));
                    // Write MMIO for sensors 16bits for Left and 16 for Rigth (in C2)
                    lval = lval<<16;
                    rval = rval&0x0000ffff;
                    updateMMIOControlAndData(MEM_IO_WRITE_SENSORS, (lval|rval));

                    // Wait till next update
                    delay = getDelay(delay, BATTERY_FREQUENCY);
                    this.sleep(delay);
                } catch (InterruptedException ex) {
                    finish = true;
                }
            }while(!finish);
        }
    }
    
    /**
     * Thread to perform a *single* solar panel movement
     */
    private class MotorMovement extends Thread {
        
        private final int nextAngle;
        
        /**
         * @param newpos new panel angle in milli-degrees
         */
        public MotorMovement(int newpos) {
            nextAngle = newpos;
        }
        
        @Override
        public void run() {
            // Set status flag for motor movement
            setStatusFlag(0, true);
            updateMMIOControlAndData(MEM_IO_STATUS, status);
            long delay;
            int step = 100; // mº per step
            int diff = (nextAngle - panelAngle);
            int steps = Math.abs(diff)/step;
            int laststep = Math.abs(diff)%step;
            if(laststep>0) steps++;
            int dir = diff>0? 1:-1; // Angle sign: positive to right, negative to left
            long stepms = (long)Math.ceil(step*(1000.0/MOTOR_SPEED)); // Last step (if required) not considered, but is OK
            movementLabel.setText("Moving");
            while(steps > 0)
            {
                try {
                    delay = System.currentTimeMillis();
                    if(steps == 1 && laststep > 0)
                        step = laststep;
                    panelAngle += (step*dir);
                    steps--;
                    angleValueLabel.setText(String.format("%.3fº", panelAngle/1000.0));
                    // UPDATE MMIO for motor position in milli-degrees
                    updateMMIOControlAndData(MEM_IO_WRITE_ANGLE, panelAngle);
                    // Wait movement
                    delay = getDelay(delay, stepms);
                    this.sleep(delay);
                    updateCanvas();
                } catch (InterruptedException ex) {
                    System.out.println("Error on sleep for MotorMovement run()");
                    System.out.println(ex.toString());
                }
            }
            movementLabel.setText("Stopped");            
            // Set status flag for motor movement
            setStatusFlag(0, false);
            motorIsMoving = false;
            updateMMIOControlAndData(MEM_IO_STATUS, status);
        }
    }
    
    /**
     * Thread to perform an automated test based on input configuration
     */
    private class SunTest extends Thread {
        public SunTest() {
        }
        
        @Override
        public void run() {
            // Check if connected to sim
            if(!isObserving())
            {
                JOptionPane.showMessageDialog(panelTools, "Please connect the tool to the sim!\n", "Connect first", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Wait if motor is moving
            if(motor != null && motorIsMoving)
                try {
                    motor.join();
                } catch (InterruptedException ex) { }
            // Ask for input string with test configuration
            String defc = "Default test";
            String config = (String)JOptionPane.showInputDialog(panelTools,
                    "Paste the configuration string (given by Blackboard) here:\n",
                    "Evaluable test",
                    JOptionPane.QUESTION_MESSAGE,
                    null,null,defc);
            if(config == null) // Cancel button
                return;
            boolean validConfig = true;
            boolean evalTest = !config.equals(defc);
            // Parse configuration, if default conf, do not modify values
            if(evalTest)
                validConfig = setConfigFromString(config);
            // If something is wrong, notify and skip test
            if(!validConfig)
            {
                JOptionPane.showMessageDialog(panelTools, "Invalid configuration provided.\n"+
                        "Use \""+defc+"\" to run a generic test.", "Test error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Start test
            // Disable controls
            sunSlider.setEnabled(false);
            testButton.setEnabled(false);
            // Set required values
            int min = sunSlider.getMinimum();
            int max = sunSlider.getMaximum();
            int val = max/4;
            int dur;
            int df;
            long[] duration = new long[TEST_CYCLES];
            double[] charge = new double[TEST_CYCLES];
            long delay;
            // Set test execution flag to 1
            setStatusFlag(1, true);
            updateMMIOControlAndData(MEM_IO_STATUS, status);
            // Set battery level and angle to 0 and wait to ensure new value
            // and to allow starting the program execution
            sunSlider.setValue(0);
            battery.setBattery(0);
            panelAngle = 0;
            angleValueLabel.setText("0º");
            failures = 0;
            try {
                this.sleep(2000);
            } catch (InterruptedException ex) {
                System.out.println("Error on sleep for SunTest run()");
                System.out.println(ex.toString());
            }
            // Each cycle cover 1 slide range
            for(int c=0; c<TEST_CYCLES; c++)
                try {
                    // Wait a while before next cycle, this allows to reposition the panel
                    this.sleep((MAX_PANEL_ANGLE-MIN_PANEL_ANGLE)/MOTOR_SPEED*100);
                    // Set the cycle to the desired time
                    dur = TEST_DURATION*1000;
                    df = Math.max(dur/(max-min+4*SUN_SHADE_SLIDER_LIMITS), 1);
                    duration[c] = 0;
                    charge[c] = batteryLevel;
                    // Cycle execution
                    for(int p=min; p<max; p++)
                    {
                        sunPos = p;
                        sunSlider.setValue(p);
                        delay = df;//(long)((Math.random()+0.3)*df);
                        if(val>p-SUN_SHADE_SLIDER_LIMITS && val<p+SUN_SHADE_SLIDER_LIMITS ||
                           max-val>p-SUN_SHADE_SLIDER_LIMITS && max-val<p+SUN_SHADE_SLIDER_LIMITS)
                            delay *= 2;
                        dur -= delay;
                        if(dur > 0)
                        {
                            duration[c] += delay; // Increment cycle duration
                            this.sleep(delay);
                        }
                    }
                    // Store cycle charge in mW
                    charge[c] = batteryLevel-charge[c];
                } catch (InterruptedException ex) {
                    System.out.println("Error on sleep for SunTest run()");
                    System.out.println(ex.toString());
                    JOptionPane.showMessageDialog(panelTools, "ERROR", "Error executing test\n"+ex.toString(), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            // Set test execution flag to 0
            setStatusFlag(1, false);
            updateMMIOControlAndData(MEM_IO_STATUS, status);
            // Generate results
            String results = "";
            long totalduration = 0;
            double totalcharge = 0;
            for(int i=0; i<TEST_CYCLES; i++)
            {
                results += String.format("Cycle %d (%ds): %.3fA \n",i,duration[i]/1000,charge[i]/1000);
                totalduration += duration[i];
                totalcharge += charge[i];
            }
            results += "Total duration: "+totalduration/1000+"s \n";
            results += String.format("Total charge: %.3fA \n",totalcharge/1000);
            if(evalTest)
            {
                // TODO GENERATE CODED OUTPUT STRING
                String eval = config+String.format("%d%.0ff%d",totalduration,totalcharge,failures);
                JOptionPane.showInputDialog(panelTools,
                    "Write the following value in Blackboard:\n",
                    "Evaluable test result",
                    JOptionPane.INFORMATION_MESSAGE,
                    null,null,eval);
                // Reset configuration
                setConfigFromString(DEFAULT_CONFIG);
            }
            else
                // Show default test results
                JOptionPane.showMessageDialog(panelTools, results, "Test results", JOptionPane.INFORMATION_MESSAGE);
            // Enable controls
            sunSlider.setValue(val);
            sunSlider.setEnabled(true);
            testButton.setEnabled(true);
        }
    }
      
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        panelTools = new javax.swing.JPanel();
        sunSlider = new javax.swing.JSlider();
        testButton = new javax.swing.JButton();
        lintLabel = new javax.swing.JLabel();
        lintValueLabel = new javax.swing.JLabel();
        poutValueLabel = new javax.swing.JLabel();
        poutLabel = new javax.swing.JLabel();
        rintLabel = new javax.swing.JLabel();
        rintValueLabel = new javax.swing.JLabel();
        angleLabel = new javax.swing.JLabel();
        angleValueLabel = new javax.swing.JLabel();
        batteryValueLabel = new javax.swing.JLabel();
        batteryLabel = new javax.swing.JLabel();
        movementLabel = new javax.swing.JLabel();
        canvas = new java.awt.Canvas();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Solar Panel");
        setBackground(new java.awt.Color(0, 0, 0));
        setMinimumSize(new java.awt.Dimension(640, 480));
        setResizable(false);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent evt) {
                formWindowClosed(evt);
            }
        });

        panelTools.setBackground(new java.awt.Color(0, 0, 0));
        panelTools.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.RAISED));
        panelTools.setForeground(new java.awt.Color(0, 0, 0));
        panelTools.setAlignmentX(0.0F);
        panelTools.setAlignmentY(0.0F);
        panelTools.setMaximumSize(new java.awt.Dimension(640, 480));
        panelTools.setMinimumSize(new java.awt.Dimension(640, 480));
        panelTools.setPreferredSize(new java.awt.Dimension(640, 480));

        sunSlider.setMaximum(1000);
        sunSlider.setValue(500);
        sunSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                sunSliderStateChanged(evt);
            }
        });

        testButton.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        testButton.setText("TEST");
        testButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                testButtonActionPerformed(evt);
            }
        });

        lintLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        lintLabel.setForeground(new java.awt.Color(255, 255, 255));
        lintLabel.setText("L-int:");

        lintValueLabel.setFont(new java.awt.Font("sansserif", 1, 14)); // NOI18N
        lintValueLabel.setForeground(new java.awt.Color(255, 255, 255));
        lintValueLabel.setText("0");

        poutValueLabel.setFont(new java.awt.Font("sansserif", 1, 14)); // NOI18N
        poutValueLabel.setForeground(new java.awt.Color(255, 255, 255));
        poutValueLabel.setText("900.000 Wh");
        poutValueLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);

        poutLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        poutLabel.setForeground(new java.awt.Color(255, 255, 255));
        poutLabel.setText("Pout:");

        rintLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        rintLabel.setForeground(new java.awt.Color(255, 255, 255));
        rintLabel.setText("R-int:");

        rintValueLabel.setFont(new java.awt.Font("sansserif", 1, 14)); // NOI18N
        rintValueLabel.setForeground(new java.awt.Color(255, 255, 255));
        rintValueLabel.setText("0");

        angleLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        angleLabel.setForeground(new java.awt.Color(255, 255, 255));
        angleLabel.setText("Angle:");

        angleValueLabel.setFont(new java.awt.Font("sansserif", 1, 14)); // NOI18N
        angleValueLabel.setForeground(new java.awt.Color(255, 255, 255));
        angleValueLabel.setText("0º");
        angleValueLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);

        batteryValueLabel.setFont(new java.awt.Font("sansserif", 1, 14)); // NOI18N
        batteryValueLabel.setForeground(new java.awt.Color(255, 255, 255));
        batteryValueLabel.setText("42.000 Ah");
        batteryValueLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);

        batteryLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        batteryLabel.setForeground(new java.awt.Color(255, 255, 255));
        batteryLabel.setText("Battery:");

        movementLabel.setFont(new java.awt.Font("sansserif", 2, 14)); // NOI18N
        movementLabel.setForeground(new java.awt.Color(255, 255, 255));
        movementLabel.setText("Stopped");

        javax.swing.GroupLayout panelToolsLayout = new javax.swing.GroupLayout(panelTools);
        panelTools.setLayout(panelToolsLayout);
        panelToolsLayout.setHorizontalGroup(
            panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelToolsLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelToolsLayout.createSequentialGroup()
                        .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addGroup(panelToolsLayout.createSequentialGroup()
                                .addComponent(testButton, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 111, Short.MAX_VALUE)
                                .addComponent(batteryLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 49, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(batteryValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 184, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(panelToolsLayout.createSequentialGroup()
                                .addComponent(lintLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(0, 0, 0)
                                .addComponent(lintValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(18, 18, 18)
                                .addComponent(poutLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(poutValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 104, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(18, 18, 18)
                                .addComponent(rintLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(0, 0, 0)
                                .addComponent(rintValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(60, 60, 60)
                        .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addGroup(panelToolsLayout.createSequentialGroup()
                                .addComponent(angleLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(angleValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 63, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(movementLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGap(1, 1, 1))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelToolsLayout.createSequentialGroup()
                        .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(sunSlider, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(canvas, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addContainerGap())))
        );
        panelToolsLayout.setVerticalGroup(
            panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelToolsLayout.createSequentialGroup()
                .addComponent(sunSlider, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(canvas, javax.swing.GroupLayout.PREFERRED_SIZE, 373, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(lintLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lintValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(rintLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(rintValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(poutLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(poutValueLabel)
                    .addComponent(angleLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(angleValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(0, 0, 0)
                .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(batteryValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 37, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(batteryLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(testButton))
                    .addComponent(movementLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(panelTools, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(panelTools, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void testButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_testButtonActionPerformed
        test = new SunTest();
        test.start();
    }//GEN-LAST:event_testButtonActionPerformed

    private void sunSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_sunSliderStateChanged
        sunPos = sunSlider.getValue();
        changeBackgroundColor();
        // TEST CODE to use slider as panel's angle controller
        //double mdegtick = (double)(MAX_PANEL_ANGLE-MIN_PANEL_ANGLE)/sunSlider.getMaximum();
        //panelAngle = (int)((sunPos*mdegtick)+MIN_PANEL_ANGLE);
        //angleValueLabel.setText(String.format("%dº",panelAngle/1000));
        // END OF TEST CODE
        updateCanvas();
    }//GEN-LAST:event_sunSliderStateChanged

    private void formWindowClosed(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosed
        performSpecialClosingDuties();
        this.dispose();
    }//GEN-LAST:event_formWindowClosed
    
    /**
     * Set the specified bit of the status flag to desired value
     * @param bit bit number [0,31]
     * @param active bit value (active = 1)
     */
    private void setStatusFlag(int bit, boolean active) {
        if(bit < 0 || bit > 31)
            return;
        int flag = 1<<bit;
        if(active)
            status = status | flag;
        else
        {
            flag = ~flag;
            status = status & flag;
        }
    }
    
    /**
     * Set the configuration from a string
     * @param config the configuration string
     * @return true for a valid configuration, false otherwise
     */
    private boolean setConfigFromString(String config) {
        try {      
            TEST_DURATION = Integer.valueOf(config.substring(1,3));
            if(TEST_DURATION <= 0) throw new NumberFormatException();
            TEST_CYCLES   = Integer.valueOf(config.substring(3,4));
            if(TEST_CYCLES <= 0) throw new NumberFormatException();
            MOTOR_SPEED   = Integer.valueOf(config.substring(4,8));
            if(MOTOR_SPEED <= 0) throw new NumberFormatException();
            MAX_OUTPUT_POWER = Integer.valueOf(config.substring(8,11))*1000;
            if(MAX_OUTPUT_POWER <= 0) throw new NumberFormatException();
        } catch (NumberFormatException | StringIndexOutOfBoundsException ex)
        {
            return false; // Invalid config
        }
        return true;
    }
    
    /**
     * @param prevTmstp previous currentTimeMillis
     * @param freq desired frequency
     * @return milliseconds to sleep a thread
     */
    private long getDelay(long prevTmstp, long freq) {
        long delay = System.currentTimeMillis()-prevTmstp;
        delay = freq-delay;
        delay/= SPEED_FACTOR;
        delay = delay>0? delay:1;
        return delay;
    }
    
    /**
     * Set the battery level to the desired porcentage
     * @param percentage if < 0, return without changes, if > 100 set full battery
     */
    private void setBatteryLevel(int percentage) {
        if(percentage <=0)
            return;
        if(percentage > 100)
        {
            batteryLevel = MAX_BATTERY_CAPACITY;
            return;
        }
        batteryLevel = (int)(MAX_BATTERY_CAPACITY * percentage/100.0);
    }
    
    /**
     * Computes the incidence angle of the Sun respect to the solar panel
     * @return the incidence angle in degrees
     */
    private double getIncidenceAngle() {
        return getSunDegrees()-panelAngle/1000.0;
    }
    
    /**
     * Computes the Sun position between [0º, 180º] (slider on left/right for min and max values)
     * @return the Sun position in degrees or -1 if Sun is in the dark side
     */
    private double getSunDegrees() {
        sunPos = sunSlider.getValue();
        if(isDark())
            return -1;
        else
            return (sunPos-SUN_SHADE_SLIDER_LIMITS)*sliderToDegrees;
    }
    
    /**
     * Check if the current slider position is within the "back side" of the panel
     * @return true if the slider is in the left or right margin determined as out of sight
     */
    private boolean isDark() {
        return sunPos<SUN_SHADE_SLIDER_LIMITS || sunPos>sunSlider.getMaximum()-SUN_SHADE_SLIDER_LIMITS;
    }
    
    /**
     * Set the background color of the frame depending on the slider position.
     * It goes from black to yellow back to black
     */
    private void changeBackgroundColor() {
        int sp = sunPos;
        if(isDark())
            sp = 0;
        else
        {
            double mid = sunSlider.getMaximum()/2;
            if(sunPos <= mid)
                sp = (int)(sp/mid*255);
            else
                sp = (int)((sunSlider.getMaximum()-sp)/mid*200);
        }
        canvas.setBackground(new Color(sp, sp, 0));
    }
    
    /**
     * Draw the solar panel line and sensors with dots
     */
    public void updateCanvas() {
        Graphics2D g = (Graphics2D)canvas.getGraphics();
        if(g == null) // Avoid exception at close
            return;
        canvas.paint(g);
        g.setStroke(new BasicStroke(PANEL_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Compute right side
        int startX = solarPanelLine[0];
        int startY = solarPanelLine[1];
        int length = solarPanelLine[2];
        int endXr = startX + (int)(Math.cos(Math.toRadians(panelAngle/1000)) * length);
        int endYr = startY + (int)(Math.sin(Math.toRadians(panelAngle/1000)) * length);
        // Compute left side
        int endXl = startX - (int)(Math.cos(Math.toRadians(panelAngle/1000)) * length);
        int endYl = startY - (int)(Math.sin(Math.toRadians(panelAngle/1000)) * length);
        // Sensors
        g.setColor(Color.GREEN);
        g.fillRoundRect(endXl-PANEL_STROKE, endYl-PANEL_STROKE, PANEL_STROKE*2, PANEL_STROKE*2, PANEL_STROKE, PANEL_STROKE*2);
        g.fillRoundRect(endXr-PANEL_STROKE, endYr-PANEL_STROKE, PANEL_STROKE*2, PANEL_STROKE*2, PANEL_STROKE, PANEL_STROKE*2);
        // Support point
        g.setColor(Color.LIGHT_GRAY);
        int ts = PANEL_STROKE*2;
        int[] vx = new int[]{startX, startX-ts, startX+ts};
        int[] vy = new int[]{startY, canvas.getHeight(), canvas.getHeight()};
        g.fillPolygon(vx, vy, 3);
        // Solar panel
        g.setColor(Color.BLUE);
        g.drawLine(startX, startY, endXl, endYl);
        g.drawLine(startX, startY, endXr, endYr);
    }
    
    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */ 
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(SolarPanel.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        
        /* Create and display the form standalone */
        java.awt.EventQueue.invokeLater(() -> {
            SolarPanel p = new SolarPanel();
            p.buildMainDisplayArea();
            p.setVisible(true);
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel angleLabel;
    javax.swing.JLabel angleValueLabel;
    private javax.swing.JLabel batteryLabel;
    javax.swing.JLabel batteryValueLabel;
    private java.awt.Canvas canvas;
    private javax.swing.JLabel lintLabel;
    javax.swing.JLabel lintValueLabel;
    private javax.swing.JLabel movementLabel;
    javax.swing.JPanel panelTools;
    private javax.swing.JLabel poutLabel;
    javax.swing.JLabel poutValueLabel;
    private javax.swing.JLabel rintLabel;
    javax.swing.JLabel rintValueLabel;
    javax.swing.JSlider sunSlider;
    javax.swing.JButton testButton;
    // End of variables declaration//GEN-END:variables
}
