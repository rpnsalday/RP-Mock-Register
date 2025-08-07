package main;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.Map;

import javax.swing.Timer;

public class MockRegister extends JFrame {
    private JTextField scanInput;
    private JTextArea virtualJournal;
    private HashMap<String, Item> priceBook;
    private HashMap<String, Integer> currentTransaction;
    private BigDecimal totalAmount;
    private JButton finishButton;
    private static final String PRICE_BOOK_FILE = "src/resources/pricebook.tsv";
    private StringBuilder scanBuffer = new StringBuilder();

    public MockRegister() {
        priceBook = new HashMap<>();
        currentTransaction = new HashMap<>();
        totalAmount = BigDecimal.ZERO;
        initUI();
        
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event instanceof KeyEvent) {
                    KeyEvent keyEvent = (KeyEvent) event;
                    if (keyEvent.getID() == KeyEvent.KEY_PRESSED) {
                        if (keyEvent.getKeyCode() == KeyEvent.VK_ENTER) {
                            String upc = scanBuffer.toString().trim();
                            scanBuffer.setLength(0);
                            
                            Item item = priceBook.get(upc);
                            if (item != null) {
                                currentTransaction.merge(upc, 1, Integer::sum);
                                totalAmount = totalAmount.add(item.price);
                                
                                updateDisplay();
                            } else {
                                virtualJournal.append(String.format("UPC: %s\nItem not found.\n\n", upc));
                            }
                            scanInput.setText("");
                        } else {
                            scanBuffer.append(keyEvent.getKeyChar());
                        }
                    }
                }
            }
        }, AWTEvent.KEY_EVENT_MASK);
        
        if (!loadPriceBook()) {
            JOptionPane.showMessageDialog(this,
                "Error loading price book from " + PRICE_BOOK_FILE,
                "Price Book Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean loadPriceBook() {
        try (BufferedReader reader = new BufferedReader(new FileReader(PRICE_BOOK_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String upc = parts[0].trim();
                    String description = parts[1].trim();
                    try {
                        BigDecimal price = new BigDecimal(parts[2].trim());
                        priceBook.put(upc, new Item(upc, description, price));
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid price format for UPC: " + upc);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void updateDisplay() {
        virtualJournal.setText(""); // Clear display
        for (Map.Entry<String, Integer> entry : currentTransaction.entrySet()) {
            Item item = priceBook.get(entry.getKey());
            virtualJournal.append(String.format("UPC: %s\nDescription: %s\nPrice: $%.2f\nQuantity: %d\nSubtotal: $%.2f\n\n",
                item.upc,
                item.description,
                item.price,
                entry.getValue(),
                item.price.multiply(new BigDecimal(entry.getValue()))));
        }
        virtualJournal.append(String.format("\nTotal Amount: $%.2f", totalAmount));
    }

    private void finishTransaction() {
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            virtualJournal.append("\n\n=== Transaction Complete ===\n");
            virtualJournal.append(String.format("Final Total: $%.2f\n", totalAmount));
            virtualJournal.append("Thank you for your purchase!\n");
            
            // Reset transaction
            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            
            // Clear after 2 seconds
            Timer timer = new Timer(2000, e -> {
                virtualJournal.setText("");
                ((Timer)e.getSource()).stop();
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    private void initUI() {
        setTitle("Mock Register");
        setSize(400, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        scanInput = new JTextField();
        virtualJournal = new JTextArea();
        virtualJournal.setEditable(false);

        JLabel scanLabel = new JLabel("Scan UPC:");
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(scanLabel, BorderLayout.WEST);
        topPanel.add(scanInput, BorderLayout.CENTER);

        finishButton = new JButton("Finish Transaction");
        finishButton.addActionListener(e -> finishTransaction());

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(virtualJournal), BorderLayout.CENTER);
        add(finishButton, BorderLayout.SOUTH);

        setVisible(true);
    }

    // Item class for price book
    static class Item {
        String upc;
        String description;
        BigDecimal price;

        Item(String upc, String description, BigDecimal price) {
            this.upc = upc;
            this.description = description;
            this.price = price;
        }
    }
}
