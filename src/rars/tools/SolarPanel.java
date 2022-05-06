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
    private static final String VERSION = "0.1";
    
    /** Memory addreses for the MMIO */
    static final int MEM_IO_WRITE_POWER   = Memory.memoryMapBaseAddress + 0x00;
    static final int MEM_IO_WRITE_SENSORS = Memory.memoryMapBaseAddress + 0x01;
    static final int MEM_IO_WRITE_ANGLE   = Memory.memoryMapBaseAddress + 0x02;
    static final int MEM_IO_READ_COMMAND  = Memory.memoryMapBaseAddress + 0x03;    
  
    /** Battery thread frequency in milliseconds */
    static final int BATTERY_FREQUENCY = 100;
    /** Sensors thread frequency in milliseconds */
    static final int SENSORS_FREQUENCY = 100;
    /** Test time in seconds */
    static final int TEST_DURATION = 10;
    /** Number of cicles to repeat the test */
    static final int TEST_CYCLES = 3;
    /** Motor speed in milli-degrees per second */
    static final int MOTOR_SPEED = 100;
    /** Solar panel maximum out power in mW */
    static final int MAX_OUTPUT_POWER = 900000;
    /** Battery capacity in mAh */
    static final int MAX_BATTERY_CAPACITY = 8000;
    /** Power bus voltage in mV */
    static final int BUS_VOLTAGE = 32000;
    /** Minimum solar panel angle in degrees */
    static final int MIN_PANEL_ANGLE = -30;
    /** Maximum solar panel angle in degrees */
    static final int MAX_PANEL_ANGLE = 30;
    /** Limit values of the slider that implies no Sun coverage */
    static final int SUN_SHADE_SLIDER_LIMITS = 50;
    /** Current battery capacity in mW */
    int batteryCapacity = MAX_BATTERY_CAPACITY/2;
    /** Current output power from solar panel in mW */
    int outputPower = 0;
    /** Current solar panel angle in degrees */
    double panelAngle = 0;
    /** Current Sun position, based on slider */
    int sunPos = 0;

    /** Battery thread */
    BatteryThread battery;
    /** Sensors thread */
    SensorsThread sensors;  
    /** Motor movement thread (single run per movement) */
    MotorMovement motor;
    /** Automatic Sun movment for test (single run per TEST) */
    SunTest test;
    
    
    public SolarPanel() {
        super(SolarPanel.TOOLNAME + ", " + SolarPanel.VERSION, SolarPanel.TOOLNAME);
    }
    
    public SolarPanel(String title, String heading) {
        super(title, heading);
    }
    
    @Override
    public String getName() {
        return SolarPanel.TOOLNAME;
    }
    
    @Override
    protected JComponent buildMainDisplayArea() {
        initComponents();
        sunSlider.setValue(sunSlider.getMaximum()/4);
        battery = new BatteryThread();
        sensors = new SensorsThread();   
        // Start threads
        battery.start();
        sensors.start();
        return panelTools;
    }
       
    @Override
    protected void performSpecialClosingDuties() {
        reset();
    }
    
    @Override
    protected void reset() {
        battery.finish();
        sensors.finish();
        batteryCapacity = MAX_BATTERY_CAPACITY/2;
        outputPower = 0;
        panelAngle = 0;
        if(motor != null)
            motor.interrupt();
        if(test != null)
            test.interrupt();        
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
        final String helpContent =
            "Use this tool to simulate the Memory Mapped IO (MMIO) for controlling a solar panel. " +
            "While this tool is connected to the program it runs a clock (starting from time 0), storing the time in milliseconds. " +
            "The memory directions are:\n" +
            "Read the command motor: "+ Binary.intToHexString(MEM_IO_READ_COMMAND) + "\n" + 
            "Write the motor position (solar panel angle):" + Binary.intToHexString(MEM_IO_WRITE_ANGLE) + "\n" +
            "Write the output power of the solar panel: "+ Binary.intToHexString(MEM_IO_WRITE_POWER) + "\n" +
            "Write the sensors measurement: "+ Binary.intToHexString(MEM_IO_WRITE_SENSORS); 
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
    
        
//    @Override
//    public void paint(Graphics g)
//    {
//        g.setColor(Color.BLUE);
//         ((java.awt.Graphics2D)g).setStroke(new java.awt.BasicStroke(3.0f));
//        int[] vx = new int[]{80, 120, 140};
//        int[] vy = new int[]{80, 50, 80};           
//        g.drawPolygon(vx, vy, 3);  
//    }
    
    /**
     * Read MMIO updates
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
            int value = memAccNotice.getValue();
        }
    }
    
    /**
     * Writes a word to a virtual memory address
     * @param dataAddr
     * @param dataValue 
     */
    private synchronized void updateMMIOControlAndData(int dataAddr, int dataValue) {
        Globals.memoryAndRegistersLock.lock();
        try {
            try {
                Globals.memory.setRawWord(dataAddr, dataValue);
            } catch (AddressErrorException aee) {
                System.out.println("Tool author specified incorrect MMIO address!" + aee);
                System.exit(0);
            }
        } finally {
            Globals.memoryAndRegistersLock.unlock();
        }
    }

    
    private class BatteryThread extends Thread {
        private boolean finish = false;
        
        public BatteryThread() {
        }
        
        private void finish() {
            finish = true;
        }
        
        @Override
        public void run() {
            do{
                try {
                    //System.out.println("battery");
                    this.sleep(700);
                } catch (InterruptedException ex) {
                    finish = true;
                }
            }while(!finish);
        }
    }
    
    private class SensorsThread extends Thread {       
        private boolean finish = false;
        
        public SensorsThread() {
        }
        
        private void finish() {
            finish = true;
        }
        
        @Override
        public void run() {
            int lval;
            int rval;
            long delay;
            do{
                try{
                    delay = System.currentTimeMillis();
                    if(isDark())
                    {
                        lval = 0;
                        rval = 0;
                    }
                    else
                    {
                        lval = (int)(Math.sin(sunPos)*10) * (int)(Math.cos(panelAngle)*10);
                        rval = (int)(Math.sin(sunPos)*10) * (int)(Math.cos(panelAngle)*10);   
                    }
                    lintValueLabel.setText(String.valueOf(lval));
                    rintValueLabel.setText(String.valueOf(rval));
                    // Write MMIO for sensors
                    //
                    delay = System.currentTimeMillis()-delay;
                    delay = delay>0? delay:1;
                    this.sleep(SENSORS_FREQUENCY-delay);
                }catch(InterruptedException ex) {
                    System.out.println("Error on sleep for SensorsThread run()");
                    System.out.println(ex.toString());
                    finish = true;
                }
            }while(!finish);
        }
    }
    
    private class MotorMovement extends Thread {
        
        protected int I=0;
        protected boolean isMoving = false;
        
        public MotorMovement() {
        }
        
        @Override
        public void run() {
            isMoving = true;
            for(I=0; I<10; I++)
                try{
                    System.out.println("MOTOR RUNNING "+I);
                    MotorMovement.sleep(1000);
                }catch(InterruptedException ex) {
                    System.out.println("Error on sleep for MotorMovement run()");
                    System.out.println(ex.toString());
                }
            isMoving = false;
        }
    }
    
    private class SunTest extends Thread {
        public SunTest() {
        }
        
        @Override
        public void run() {
            // Disable controls
            sunSlider.setEnabled(false);
            testButton.setEnabled(false);
            // Set required values
            int val = sunSlider.getValue();
            int min = sunSlider.getMinimum();
            int max = sunSlider.getMaximum();          
            long[] duration = new long[TEST_CYCLES];
            int[] charge = new int[TEST_CYCLES];
            long delay;
                        
            // Each cycle cover 1 slide range
            for(int c=0; c<TEST_CYCLES; c++)
            {
                // Try to set the cycle to the time desired
                int dur = TEST_DURATION*1000;
                int df = dur/(max-min-2*SUN_SHADE_SLIDER_LIMITS);
                df = (df>=0)?df:1;
                long ct = System.currentTimeMillis();
                
                // Cycle execution
                for(int p=min; p<max; p++)
                {
                    sunPos = p;
                    sunSlider.setValue(p);
                    try {
                        delay = (long)(Math.random()*df);
                        delay = (delay>1)?delay:df;
                        if(val>p-SUN_SHADE_SLIDER_LIMITS && val<p+SUN_SHADE_SLIDER_LIMITS)
                            delay *= 2;
                        dur -= delay;
                        if(dur > 0)
                            this.sleep(delay);
                    } catch (InterruptedException ex) {
                        System.out.println("Error on sleep for SunTest run()");
                        System.out.println(ex.toString());
                    }
                }
                // Store cycle duration in ms and charge in mW
                duration[c] = System.currentTimeMillis()-ct;
                charge[c] = c; //UPDATE
            }
            
            // Generate results
            String results = "";
            long totalduration = 0;
            int totalcharge = 0;
            for(int i=0; i<TEST_CYCLES; i++)
            {
                results += "Cycle "+i+" ("+duration[i]+" ms): "+charge[i]+" mW \n";
                totalduration += duration[i];
                totalcharge += charge[i];
            }
            results += "Total duration: "+totalduration+" ms \n";
            results += "Total charge: "+totalcharge+" mW \n";
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
        poutWLabel = new javax.swing.JLabel();
        poutLabel = new javax.swing.JLabel();
        rintLabel = new javax.swing.JLabel();
        rintValueLabel = new javax.swing.JLabel();
        angleLabel = new javax.swing.JLabel();
        angleValueLabel = new javax.swing.JLabel();
        angleDegLabel = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Solar Panel");
        setBackground(new java.awt.Color(0, 0, 0));
        setMaximumSize(new java.awt.Dimension(640, 480));
        setMinimumSize(new java.awt.Dimension(640, 480));
        setResizable(false);

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
        poutValueLabel.setText("100000");
        poutValueLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);

        poutWLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        poutWLabel.setForeground(new java.awt.Color(255, 255, 255));
        poutWLabel.setText("mWh");

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
        angleValueLabel.setText("-30.00");
        angleValueLabel.setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);

        angleDegLabel.setFont(new java.awt.Font("sansserif", 0, 14)); // NOI18N
        angleDegLabel.setForeground(new java.awt.Color(255, 255, 255));
        angleDegLabel.setText("º");

        javax.swing.GroupLayout panelToolsLayout = new javax.swing.GroupLayout(panelTools);
        panelTools.setLayout(panelToolsLayout);
        panelToolsLayout.setHorizontalGroup(
            panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelToolsLayout.createSequentialGroup()
                .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(sunSlider, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, 635, Short.MAX_VALUE)
                    .addGroup(panelToolsLayout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(testButton, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(18, 18, 18)
                        .addComponent(lintLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(lintValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(30, 30, 30)
                        .addComponent(poutLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(poutValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(poutWLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(30, 30, 30)
                        .addComponent(rintLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(rintValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(60, 60, 60)
                        .addComponent(angleLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 47, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(angleValueLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 44, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addGap(0, 0, 0)
                        .addComponent(angleDegLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(1, 1, 1))
        );
        panelToolsLayout.setVerticalGroup(
            panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelToolsLayout.createSequentialGroup()
                .addComponent(sunSlider, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 418, Short.MAX_VALUE)
                .addGroup(panelToolsLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(testButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lintLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lintValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(rintLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(rintValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(angleDegLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(poutLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(poutWLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(poutValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(angleLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(angleValueLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
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

        double mid = sunSlider.getMaximum()/2;
        if(sunPos <= mid)
        panelAngle = -(mid-sunPos)/45;
        else
        panelAngle = (sunSlider.getMaximum()-sunPos)/45.0;

        angleValueLabel.setText(String.format("%.2f", panelAngle));

        Graphics2D g = (Graphics2D)this.getGraphics();
        g.setStroke(new java.awt.BasicStroke(5.0f));
        g.setColor(Color.BLUE);
        solarPanelLine.setLine(170,400,470,400);

        java.awt.geom.AffineTransform at =
        java.awt.geom.AffineTransform.getRotateInstance(
            Math.toRadians(panelAngle), solarPanelLine.getX1(), solarPanelLine.getY1());

        g.draw(at.createTransformedShape(solarPanelLine));

    }//GEN-LAST:event_sunSliderStateChanged

    private boolean isDark() {
        return sunPos<SUN_SHADE_SLIDER_LIMITS || sunPos>sunSlider.getMaximum()-SUN_SHADE_SLIDER_LIMITS;
    }
    
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
                sp = (int)((sunSlider.getMaximum()-sp)/mid*255);
        }
        Color bc = new Color(sp, sp, 0);
        panelTools.setBackground(bc);
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

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(() -> {
            SolarPanel p = new SolarPanel();
            p.buildMainDisplayArea();
            p.setVisible(true);
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel angleDegLabel;
    private javax.swing.JLabel angleLabel;
    javax.swing.JLabel angleValueLabel;
    private javax.swing.JLabel lintLabel;
    javax.swing.JLabel lintValueLabel;
    javax.swing.JPanel panelTools;
    private javax.swing.JLabel poutLabel;
    javax.swing.JLabel poutValueLabel;
    private javax.swing.JLabel poutWLabel;
    private javax.swing.JLabel rintLabel;
    javax.swing.JLabel rintValueLabel;
    javax.swing.JSlider sunSlider;
    javax.swing.JButton testButton;
    // End of variables declaration//GEN-END:variables
    java.awt.geom.Line2D solarPanelLine = new java.awt.geom.Line2D.Double(170,400,470,400);;
}
