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
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MockRegister extends JFrame {
    private JTextArea virtualJournal;
    private HashMap<String, Item> priceBook;
    private HashMap<String, Integer> currentTransaction;
    private BigDecimal totalAmount;
    private JButton finishButton;
    private static final String PRICE_BOOK_FILE = "src/resources/pricebook.tsv";
    private StringBuilder scanBuffer = new StringBuilder();
    private JButton payButton;
    private JButton cancelButton;
    private JButton holdButton;
    private JButton printButton;
    private ArrayList<HashMap<String, Integer>> heldOrders = new ArrayList<>();
    private BigDecimal[] heldTotals = new BigDecimal[5]; // Support up to 5 held orders
	private JButton retrieveButton;

    public MockRegister() {
        priceBook = new HashMap<>();
        currentTransaction = new HashMap<>();
        totalAmount = BigDecimal.ZERO;
        initUI();
        
        // Global key listener for scan gun input
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event instanceof KeyEvent) {
                    KeyEvent keyEvent = (KeyEvent) event;
                    if (keyEvent.getID() == KeyEvent.KEY_PRESSED) {
                        if (keyEvent.getKeyCode() == KeyEvent.VK_ENTER) {
                            String upc = scanBuffer.toString().trim();
                            scanBuffer.setLength(0); // Clear buffer
                            
                            Item item = priceBook.get(upc);
                            if (item != null) {
                                currentTransaction.merge(upc, 1, Integer::sum);
                                totalAmount = totalAmount.add(item.price);
                                updateDisplay();
                            } else {
                                virtualJournal.append(String.format("UPC: %s\nItem not found.\n\n", upc));
                            }
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

private void initUI() {
    setTitle("Mock Register");
    setSize(500, 500);
    setDefaultCloseOperation(EXIT_ON_CLOSE);
    setLayout(new BorderLayout());

    virtualJournal = new JTextArea();
    virtualJournal.setEditable(false);
    virtualJournal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    
    // Create button panel
    JPanel buttonPanel = new JPanel(new GridLayout(1, 5, 5, 5));
    
    payButton = new JButton("Pay");
    cancelButton = new JButton("Cancel Order");
    holdButton = new JButton("Hold Order");
    printButton = new JButton("Print Receipt");
    
    payButton.addActionListener(e -> finishTransaction());
    cancelButton.addActionListener(e -> cancelOrder());
    holdButton.addActionListener(e -> holdOrder());
    printButton.addActionListener(e -> printReceipt());
    
    buttonPanel.add(payButton);
    buttonPanel.add(cancelButton);
    buttonPanel.add(holdButton);
    buttonPanel.add(printButton);
	
	// Add this to initUI() where other buttons are created
	retrieveButton = new JButton("Retrieve Order");
	retrieveButton.addActionListener(e -> retrieveHeldOrder());
	buttonPanel.add(retrieveButton);
    
    // Add padding around button panel
    JPanel southPanel = new JPanel(new BorderLayout());
    southPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    southPanel.add(buttonPanel, BorderLayout.CENTER);

    add(new JScrollPane(virtualJournal), BorderLayout.CENTER);
    add(southPanel, BorderLayout.SOUTH);

    setVisible(true);
}

private void cancelOrder() {
    if (currentTransaction.isEmpty()) {
        JOptionPane.showMessageDialog(this,
            "No active transaction to cancel.",
            "Cancel Order",
            JOptionPane.INFORMATION_MESSAGE);
        return;
    }
    
    int result = JOptionPane.showConfirmDialog(this,
        "Are you sure you want to cancel this order?",
        "Cancel Order",
        JOptionPane.YES_NO_OPTION);
        
    if (result == JOptionPane.YES_OPTION) {
        currentTransaction.clear();
        totalAmount = BigDecimal.ZERO;
        updateDisplay();
        JOptionPane.showMessageDialog(this,
            "Order has been cancelled.",
            "Order Cancelled",
            JOptionPane.INFORMATION_MESSAGE);
    }
}

private void holdOrder() {
    if (currentTransaction.isEmpty()) {
        JOptionPane.showMessageDialog(this,
            "No active transaction to hold.",
            "Hold Order",
            JOptionPane.INFORMATION_MESSAGE);
        return;
    }
    
    // Find next available hold slot
    int holdSlot = -1;
    for (int i = 0; i < heldTotals.length; i++) {
        if (heldTotals[i] == null) {
            holdSlot = i;
            break;
        }
    }
    
    if (holdSlot == -1) {
        JOptionPane.showMessageDialog(this,
            "Maximum number of held orders reached.",
            "Hold Order",
            JOptionPane.WARNING_MESSAGE);
        return;
    }
    
    // Store the current order
    heldOrders.add(new HashMap<>(currentTransaction));
    heldTotals[holdSlot] = totalAmount;
    
    // Clear current transaction
    currentTransaction.clear();
    totalAmount = BigDecimal.ZERO;
    updateDisplay();
    
    JOptionPane.showMessageDialog(this,
        "Order held in slot " + (holdSlot + 1),
        "Order Held",
        JOptionPane.INFORMATION_MESSAGE);
}

private void printReceipt() {
    if (currentTransaction.isEmpty()) {
        JOptionPane.showMessageDialog(this,
            "No active transaction to print.",
            "Print Receipt",
            JOptionPane.INFORMATION_MESSAGE);
        return;
    }
    
    StringBuilder receipt = new StringBuilder();
    receipt.append("\n=== MOCK REGISTER RECEIPT ===\n");
    receipt.append(String.format("Date: %s\n", 
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
    receipt.append("-".repeat(40)).append("\n\n");
    
    // Add current display content
    receipt.append(virtualJournal.getText());
    receipt.append("\n\n").append("-".repeat(40)).append("\n");
    receipt.append("Thank you for shopping with us!\n");
    
    // In a real system, this would go to a printer
    // For now, we'll show it in a dialog
    JTextArea receiptArea = new JTextArea(receipt.toString());
    receiptArea.setEditable(false);
    receiptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    
    JScrollPane scrollPane = new JScrollPane(receiptArea);
    scrollPane.setPreferredSize(new Dimension(400, 400));
    
    JOptionPane.showMessageDialog(this,
        scrollPane,
        "Print Preview",
        JOptionPane.INFORMATION_MESSAGE);
}

// Modify the existing finishTransaction to be our pay function
private void finishTransaction() {
    if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
        JOptionPane.showMessageDialog(this,
            "No active transaction to complete.",
            "Payment",
            JOptionPane.INFORMATION_MESSAGE);
        return;
    }

    // In a real system, you would process payment here
    int result = JOptionPane.showConfirmDialog(this,
        String.format("Total amount to pay: $%.2f\nProceed with payment?", totalAmount),
        "Payment Confirmation",
        JOptionPane.YES_NO_OPTION);
        
    if (result == JOptionPane.YES_OPTION) {
        printReceipt(); // Automatically print receipt upon successful payment
        
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
        
        // Add headers with proper spacing
        virtualJournal.append(String.format("%-8s %-30s %-10s %-10s\n", 
            "Qty", "Name", "Price", "Subtotal"));
        virtualJournal.append("-".repeat(60) + "\n");

        for (Map.Entry<String, Integer> entry : currentTransaction.entrySet()) {
            Item item = priceBook.get(entry.getKey());
            BigDecimal subtotal = item.price.multiply(new BigDecimal(entry.getValue()));
            virtualJournal.append(String.format("%-8d %-30s $%-9.2f $%-9.2f\n",
                entry.getValue(),
                truncateString(item.description, 30),
                item.price,
                subtotal));
        }
        virtualJournal.append("\n" + "-".repeat(60) + "\n");
        virtualJournal.append(String.format("%50s $%.2f", "Total Amount:", totalAmount));
    }

    // Helper method to truncate strings that are too long
    private String truncateString(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength - 3) + "...";
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
	
	// Add this new method
	private void retrieveHeldOrder() {
		if (!currentTransaction.isEmpty()) {
			int result = JOptionPane.showConfirmDialog(this,
				"Current transaction will be cancelled. Continue?",
				"Retrieve Held Order",
				JOptionPane.YES_NO_OPTION);
			if (result != JOptionPane.YES_OPTION) {
				return;
			}
		}
	
		// Create list of held orders for selection
		StringBuilder ordersList = new StringBuilder();
		for (int i = 0; i < heldTotals.length; i++) {
			if (heldTotals[i] != null) {
				ordersList.append(String.format("Order %d - $%.2f\n", i + 1, heldTotals[i]));
			}
		}
	
		if (ordersList.length() == 0) {
			JOptionPane.showMessageDialog(this,
				"No orders are currently held.",
				"Retrieve Order",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
	
		// Show dialog to select order
		String input = JOptionPane.showInputDialog(this,
			"Enter order number to retrieve:\n\n" + ordersList.toString(),
			"Retrieve Held Order",
			JOptionPane.QUESTION_MESSAGE);
	
		if (input != null && !input.trim().isEmpty()) {
			try {
				int orderNum = Integer.parseInt(input.trim()) - 1;
				if (orderNum >= 0 && orderNum < heldTotals.length && heldTotals[orderNum] != null) {
					// Clear current transaction
					currentTransaction.clear();
					totalAmount = BigDecimal.ZERO;
	
					// Retrieve held order
					currentTransaction.putAll(heldOrders.get(orderNum));
					totalAmount = heldTotals[orderNum];
	
					// Remove from held orders
					heldOrders.remove(orderNum);
					heldTotals[orderNum] = null;
	
					// Update display
					updateDisplay();
					JOptionPane.showMessageDialog(this,
						"Order retrieved successfully.",
						"Order Retrieved",
						JOptionPane.INFORMATION_MESSAGE);
				} else {
					JOptionPane.showMessageDialog(this,
						"Invalid order number.",
						"Error",
						JOptionPane.ERROR_MESSAGE);
				}
			} catch (NumberFormatException e) {
				JOptionPane.showMessageDialog(this,
					"Please enter a valid number.",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		}
	}
}
