package main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MockRegister extends JFrame {
    private JTextArea virtualJournal;
    private HashMap<String, Integer> currentTransaction;
    private BigDecimal totalAmount;
    private static final String PRICE_BOOK_FILE = "src/resources/pricebook.tsv";
    private StringBuilder scanBuffer = new StringBuilder();
    private JButton payButton;
    private JButton cancelButton;
    private JButton holdButton;
    private JButton retrieveButton;
    private JButton printButton;
    private JButton viewTransactionsButton;
    private JDialog transactionsDialog;
    private JTextArea transactionsTextArea;

    public MockRegister() {
        currentTransaction = new HashMap<>();
        totalAmount = BigDecimal.ZERO;

        // Initialize database and load price book
        Database.initializeDatabase();
        Database.loadPriceBook(PRICE_BOOK_FILE);

        initUI();
        setupKeyListener();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Database.closeConnection();
            }
        });
    }

    private void initUI() {
        setTitle("Mock Register");
        setSize(700, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Initialize text area with monospaced font for aligned columns
        virtualJournal = new JTextArea();
        virtualJournal.setEditable(false);
        virtualJournal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // Create header
        updateDisplay();

        // Create button panel
        JPanel buttonPanel = new JPanel(new GridLayout(1, 5, 5, 5));
        
        payButton = new JButton("Pay");
        cancelButton = new JButton("Cancel Order");
        holdButton = new JButton("Hold Order");
        retrieveButton = new JButton("Retrieve Order");
        printButton = new JButton("Print Receipt");
        
        // Add action listeners
        payButton.addActionListener(e -> finishTransaction());
        cancelButton.addActionListener(e -> cancelOrder());
        holdButton.addActionListener(e -> holdOrder());
        retrieveButton.addActionListener(e -> retrieveHeldOrder());
        printButton.addActionListener(e -> printReceipt());

        buttonPanel.add(payButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(holdButton);
        buttonPanel.add(retrieveButton);
        buttonPanel.add(printButton);

        viewTransactionsButton = new JButton("View Transactions");
        viewTransactionsButton.addActionListener(e -> showTransactionHistory());
        buttonPanel.add(viewTransactionsButton);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        southPanel.add(buttonPanel, BorderLayout.CENTER);

        add(virtualJournal, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        setVisible(true);
    }

    private void setupKeyListener() {
        // Global key listener for scan gun input
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event instanceof KeyEvent) {
                    KeyEvent keyEvent = (KeyEvent) event;
                    if (keyEvent.getID() == KeyEvent.KEY_PRESSED) {
                        if (keyEvent.getKeyCode() == KeyEvent.VK_ENTER) {
                            String upc = scanBuffer.toString().trim();
                            scanBuffer.setLength(0);
                            
                            System.out.println("\n[SCAN GUN] Scanning UPC: " + upc);
                            
                            Item item = Database.getItem(upc);
                            if (item != null) {
                                System.out.println("[SCAN GUN] Item found: " + item.description + " - $" + item.price);
                                currentTransaction.merge(upc, 1, Integer::sum);
                                totalAmount = calculateTotal();
                                updateDisplay();
                            } else {
                                System.out.println("[SCAN GUN] ERROR: Item not found for UPC: " + upc);
                                virtualJournal.append(String.format("\n\nUPC: %sItem not found.\n\n", upc));
                            }
                        } else {
                            scanBuffer.append(keyEvent.getKeyChar());
                        }
                    }
                }
            }
        }, AWTEvent.KEY_EVENT_MASK);
    }

    private void updateDisplay() {
        virtualJournal.setText(""); // Clear display
        
        // Add headers with proper spacing
        virtualJournal.append(String.format("%-8s %-50s %-30s %-30s\n",
            "Qty", "Name", "Price", "Subtotal"));
        virtualJournal.append("-".repeat(100) + "\n");

        for (var entry : currentTransaction.entrySet()) {
            Item item = Database.getItem(entry.getKey());
            if (item != null) {
                BigDecimal subtotal = item.price.multiply(new BigDecimal(entry.getValue()));
                virtualJournal.append(String.format("%-8d %-50s $%-29.2f $%-29.2f\n",
                    entry.getValue(),
                    truncateString(item.description, 50),
                    item.price,
                    subtotal));
            }
        }

        virtualJournal.append("\n" + "-".repeat(100) + "\n");
        virtualJournal.append(String.format("%92s $%.2f", "Total Amount:", totalAmount));
    }

    private String truncateString(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength - 3) + "...";
    }

    private BigDecimal calculateTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : currentTransaction.entrySet()) {
            Item item = Database.getItem(entry.getKey());
            if (item != null) {
                total = total.add(item.price.multiply(new BigDecimal(entry.getValue())));
            }
        }
        return total;
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
        
        long holdId = Database.holdOrder(currentTransaction, totalAmount);
        if (holdId != -1) {
            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            updateDisplay();
            Database.printAllTables();  // Add this line
            
            JOptionPane.showMessageDialog(this,
                "Order held successfully. Order ID: " + holdId,
                "Order Held",
                JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void retrieveHeldOrder() {
        String input = JOptionPane.showInputDialog(this,
            "Enter order ID to retrieve:",
            "Retrieve Held Order",
            JOptionPane.QUESTION_MESSAGE);

        if (input != null && !input.trim().isEmpty()) {
            try {
                long orderId = Long.parseLong(input.trim());
                HashMap<String, Integer> items = Database.retrieveHeldOrder(orderId);
                
                if (!items.isEmpty()) {
                    currentTransaction = items;
                    totalAmount = calculateTotal();
                    updateDisplay();
                    JOptionPane.showMessageDialog(this,
                        "Order retrieved successfully.",
                        "Order Retrieved",
                        JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Order not found.",
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
        
        receipt.append(virtualJournal.getText());
        receipt.append("\n\n").append("-".repeat(40)).append("\n");
        receipt.append("Thank you for shopping with us!\n");
        
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

    // Add this new method to the class
    private void showTransactionHistory() {
        if (transactionsDialog == null) {
            transactionsDialog = new JDialog(this, "Transaction History", false);
            transactionsDialog.setSize(800, 600);
            transactionsDialog.setLayout(new BorderLayout());

            transactionsTextArea = new JTextArea();
            transactionsTextArea.setEditable(false);
            transactionsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JButton refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> updateTransactionHistory());

            JScrollPane scrollPane = new JScrollPane(transactionsTextArea);
            transactionsDialog.add(scrollPane, BorderLayout.CENTER);
            transactionsDialog.add(refreshButton, BorderLayout.SOUTH);

            // Center the dialog relative to the main window
            transactionsDialog.setLocationRelativeTo(this);
        }

        updateTransactionHistory();
        transactionsDialog.setVisible(true);
    }

    private void updateTransactionHistory() {
        transactionsTextArea.setText(Database.getTransactionHistory());
    }

    // Modify the finishTransaction method to update transaction history after a new transaction
    private void finishTransaction() {
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this,
                "No active transaction to complete.",
                "Payment",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
            String.format("Total amount to pay: $%.2f\nProceed with payment?", totalAmount),
            "Payment Confirmation",
            JOptionPane.YES_NO_OPTION);
        
        if (result == JOptionPane.YES_OPTION) {
            long transactionId = Database.saveTransaction(currentTransaction, totalAmount);
            if (transactionId != -1) {
                printReceipt();
                currentTransaction.clear();
                totalAmount = BigDecimal.ZERO;
                updateDisplay();
            
                // Update transaction history if the dialog is open
                if (transactionsDialog != null && transactionsDialog.isVisible()) {
                    updateTransactionHistory();
                }
            }
        }
    }
}