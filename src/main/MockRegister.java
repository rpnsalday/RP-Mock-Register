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
        // Set application properties
        setTitle("Mock Register");
        setSize(800, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Set custom look and feel colors
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Define color scheme
        Color primaryColor = new Color(41, 128, 185); // Blue
        Color secondaryColor = new Color(52, 152, 219); // Lighter blue
        Color accentColor = new Color(231, 76, 60);  // Red for cancel/important actions
        Color backgroundColor = new Color(236, 240, 241); // Light gray background
        Color textColor = new Color(44, 62, 80); // Dark blue-gray for text
        
        // Set frame background
        getContentPane().setBackground(backgroundColor);
        
        // Create a header panel
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(primaryColor);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        
        JLabel titleLabel = new JLabel("Mock Register");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);
        
        // Add current date/time to header
        JLabel dateTimeLabel = new JLabel(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        dateTimeLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        dateTimeLabel.setForeground(Color.WHITE);
        headerPanel.add(dateTimeLabel, BorderLayout.EAST);
        
        // Initialize text area with improved styling
        virtualJournal = new JTextArea();
        virtualJournal.setEditable(false);
        virtualJournal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        virtualJournal.setBackground(Color.WHITE);
        virtualJournal.setForeground(textColor);
        virtualJournal.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Add scroll pane for the journal
        JScrollPane scrollPane = new JScrollPane(virtualJournal);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        
        // Create header
        updateDisplay();
        
        // Create styled button panel with better layout
        JPanel buttonPanel = new JPanel(new GridLayout(2, 3, 10, 10));
        buttonPanel.setBackground(backgroundColor);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Create and style buttons
        payButton = createStyledButton("Pay", primaryColor, Color.WHITE);
        cancelButton = createStyledButton("Cancel Order", accentColor, Color.WHITE);
        holdButton = createStyledButton("Hold Order", secondaryColor, Color.WHITE);
        retrieveButton = createStyledButton("Retrieve Order", secondaryColor, Color.WHITE);
        printButton = createStyledButton("Print Receipt", secondaryColor, Color.WHITE);
        viewTransactionsButton = createStyledButton("View Transactions", secondaryColor, Color.WHITE);
        
        // Add action listeners
        payButton.addActionListener(e -> finishTransaction());
        cancelButton.addActionListener(e -> cancelOrder());
        holdButton.addActionListener(e -> holdOrder());
        retrieveButton.addActionListener(e -> retrieveHeldOrder());
        printButton.addActionListener(e -> printReceipt());
        viewTransactionsButton.addActionListener(e -> showTransactionHistory());
        
        // Add buttons to panel
        buttonPanel.add(payButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(holdButton);
        buttonPanel.add(retrieveButton);
        buttonPanel.add(printButton);
        buttonPanel.add(viewTransactionsButton);
        
        // Create a status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(backgroundColor);
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 15, 10, 15));
        
        JLabel statusLabel = new JLabel("Ready to scan items");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        statusLabel.setForeground(textColor);
        statusPanel.add(statusLabel, BorderLayout.WEST);
        
        // Assemble the UI
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(backgroundColor);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBackground(backgroundColor);
        southPanel.add(buttonPanel, BorderLayout.CENTER);
        southPanel.add(statusPanel, BorderLayout.SOUTH);
        
        add(headerPanel, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);
        
        setVisible(true);
    }
    
    // Helper method to create consistently styled buttons
    private JButton createStyledButton(String text, Color bgColor, Color fgColor) {
        JButton button = new JButton(text);
        button.setBackground(bgColor);
        button.setForeground(fgColor);
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(true);
        button.setContentAreaFilled(true);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bgColor.darker(), 1),
            BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));
        
        // Add hover effect
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(bgColor.darker());
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(bgColor);
            }
        });
        
        return button;
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
        
        // Add store header
        virtualJournal.append("MOCK REGISTER STORE\n");
        virtualJournal.append("Transaction: " + 
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "\n");
        virtualJournal.append("\n");
        
        // Add headers with proper spacing and styling
        String headerLine = String.format("%-8s %-50s %-15s %-15s\n",
            "QTY", "ITEM DESCRIPTION", "PRICE", "SUBTOTAL");
        virtualJournal.append(headerLine);
        
        // Add separator line with double line character
        virtualJournal.append("=".repeat(90) + "\n");

        // If cart is empty, show message
        if (currentTransaction.isEmpty()) {
            virtualJournal.append("\n\n");
            virtualJournal.append("          Cart is empty. Scan items to add them to your transaction.\n");
            virtualJournal.append("\n\n");
        } else {
            // Display items with improved formatting
            for (var entry : currentTransaction.entrySet()) {
                Item item = Database.getItem(entry.getKey());
                if (item != null) {
                    BigDecimal subtotal = item.price.multiply(new BigDecimal(entry.getValue()));
                    virtualJournal.append(String.format("%-8d %-50s $%-14.2f $%-14.2f\n",
                        entry.getValue(),
                        truncateString(item.description, 50),
                        item.price,
                        subtotal));
                }
            }
        }

        // Add separator before total
        virtualJournal.append("\n" + "-".repeat(90) + "\n");
        
        // Format total with better alignment and styling
        String totalLine = String.format("%76s $%-12.2f", "TOTAL:", totalAmount);
        virtualJournal.append(totalLine);
        
        // Add footer with instructions
        virtualJournal.append("\n\n");
        virtualJournal.append("Scan items to add to cart. Use buttons below to complete transaction.");
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

    private void printReceipt(BigDecimal amountTendered, BigDecimal change) {
        // Define colors for receipt
        Color receiptBgColor = new Color(255, 253, 245); // Slight cream color for receipt paper
        Color receiptTextColor = new Color(50, 50, 50);  // Dark gray for text

        // Create styled receipt
        StringBuilder receipt = new StringBuilder();

        // Receipt header
        receipt.append("\n");
        receipt.append("           MOCK REGISTER STORE\n");
        receipt.append("           OFFICIAL RECEIPT\n\n");

        // Receipt details
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        receipt.append(String.format("Date: %s\n", dateTime));
        receipt.append(String.format("Transaction #: %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))));
        receipt.append("Cashier: SYSTEM\n");
        receipt.append("\n");

        // Receipt items header
        receipt.append(String.format("%-5s %-40s %10s %12s\n", "QTY", "ITEM", "PRICE", "AMOUNT"));
        receipt.append("=".repeat(70)).append("\n");

        // Receipt items
        for (var entry : currentTransaction.entrySet()) {
            Item item = Database.getItem(entry.getKey());
            if (item != null) {
                BigDecimal subtotal = item.price.multiply(new BigDecimal(entry.getValue()));
                receipt.append(String.format("%-5d %-40s %10.2f %12.2f\n",
                        entry.getValue(),
                        truncateString(item.description, 40),
                        item.price.doubleValue(),
                        subtotal.doubleValue()));
            }
        }

        // Receipt footer
        receipt.append("-".repeat(70)).append("\n");
        receipt.append(String.format("%58s %12.2f\n", "SUBTOTAL:", totalAmount.doubleValue()));
        receipt.append(String.format("%58s %12.2f\n", "TAX (0%):", 0.00));
        receipt.append(String.format("%58s %12.2f\n", "TOTAL:", totalAmount.doubleValue()));
        receipt.append("\n");
        receipt.append("Payment Method: CASH\n");
        receipt.append(String.format("Amount Tendered: $%.2f\n", amountTendered.doubleValue()));
        receipt.append(String.format("Change: $%.2f\n", change.doubleValue()));
        receipt.append("\n");
        receipt.append("=".repeat(70)).append("\n");
        receipt.append("           Thank you for shopping with us!\n");
        receipt.append("              Please come again soon.\n");
        receipt.append("\n");

        // Create styled receipt area
        JTextArea receiptArea = new JTextArea(receipt.toString());
        receiptArea.setEditable(false);
        receiptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        receiptArea.setBackground(receiptBgColor);
        receiptArea.setForeground(receiptTextColor);
        receiptArea.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Create styled scroll pane
        JScrollPane scrollPane = new JScrollPane(receiptArea);
        scrollPane.setPreferredSize(new Dimension(500, 500));
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));

        // Create custom dialog for receipt
        JDialog receiptDialog = new JDialog(this, "Receipt Preview", true);
        receiptDialog.setLayout(new BorderLayout());
        receiptDialog.add(scrollPane, BorderLayout.CENTER);

        // Add print and close buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(receiptBgColor);

        JButton printButton = new JButton("Print");
        printButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(receiptDialog,
                    "Receipt sent to printer.",
                    "Printing",
                    JOptionPane.INFORMATION_MESSAGE);
            receiptDialog.dispose();
        });

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> receiptDialog.dispose());

        buttonPanel.add(printButton);
        buttonPanel.add(closeButton);
        receiptDialog.add(buttonPanel, BorderLayout.SOUTH);

        // Show dialog
        receiptDialog.pack();
        receiptDialog.setLocationRelativeTo(this);
        receiptDialog.setVisible(true);
    }

    private void printReceipt() {
        printReceipt(totalAmount, BigDecimal.ZERO);
    }
    private void showTransactionHistory() {
        // Define colors for transaction history
        Color primaryColor = new Color(41, 128, 185); // Blue
        Color backgroundColor = new Color(236, 240, 241); // Light gray background
        Color textColor = new Color(44, 62, 80); // Dark blue-gray for text
        
        if (transactionsDialog == null) {
            // Create a styled dialog
            transactionsDialog = new JDialog(this, "Transaction History", false);
            transactionsDialog.setSize(900, 600);
            transactionsDialog.setLayout(new BorderLayout());
            transactionsDialog.getContentPane().setBackground(backgroundColor);
            
            // Create header panel
            JPanel headerPanel = new JPanel(new BorderLayout());
            headerPanel.setBackground(primaryColor);
            headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
            
            JLabel titleLabel = new JLabel("Transaction History");
            titleLabel.setFont(new Font("Arial", Font.BOLD, 20));
            titleLabel.setForeground(Color.WHITE);
            headerPanel.add(titleLabel, BorderLayout.WEST);
            
            // Create styled text area
            transactionsTextArea = new JTextArea();
            transactionsTextArea.setEditable(false);
            transactionsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            transactionsTextArea.setBackground(Color.WHITE);
            transactionsTextArea.setForeground(textColor);
            transactionsTextArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            // Create styled scroll pane
            JScrollPane scrollPane = new JScrollPane(transactionsTextArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            // Create button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.setBackground(backgroundColor);
            buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            // Create styled refresh button
            JButton refreshButton = createStyledButton("Refresh", primaryColor, Color.WHITE);
            refreshButton.addActionListener(e -> updateTransactionHistory());
            
            // Create styled close button
            JButton closeButton = createStyledButton("Close", new Color(149, 165, 166), Color.WHITE);
            closeButton.addActionListener(e -> transactionsDialog.setVisible(false));
            
            // Add buttons to panel
            buttonPanel.add(refreshButton);
            buttonPanel.add(closeButton);
            
            // Add components to dialog
            transactionsDialog.add(headerPanel, BorderLayout.NORTH);
            transactionsDialog.add(scrollPane, BorderLayout.CENTER);
            transactionsDialog.add(buttonPanel, BorderLayout.SOUTH);
            
            // Center the dialog relative to the main window
            transactionsDialog.setLocationRelativeTo(this);
        }
        
        // Update and show dialog
        updateTransactionHistory();
        transactionsDialog.setVisible(true);
    }
    
    private void updateTransactionHistory() {
        // Get transaction history from database
        String historyText = Database.getTransactionHistory();
        
        // If empty, show a message
        if (historyText == null || historyText.trim().isEmpty()) {
            transactionsTextArea.setText("No transaction history available.");
            return;
        }
        
        // Format the transaction history with better styling
        StringBuilder formattedHistory = new StringBuilder();
        formattedHistory.append("TRANSACTION HISTORY\n");
        formattedHistory.append("=".repeat(80)).append("\n\n");
        formattedHistory.append(historyText);
        
        // Set the formatted text
        transactionsTextArea.setText(formattedHistory.toString());
        
        // Scroll to the top
        transactionsTextArea.setCaretPosition(0);
    }

    private void finishTransaction() {
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this,
                    "No active transaction to complete.",
                    "Payment",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        BigDecimal amountTendered = null;

        // Keep prompting until valid amount is entered
        while (true) {
            String input = JOptionPane.showInputDialog(this,
                    String.format("Total: $%.2f\nEnter amount tendered:", totalAmount),
                    "Enter Payment",
                    JOptionPane.QUESTION_MESSAGE);

            if (input == null) {
                // User cancelled
                return;
            }

            try {
                amountTendered = new BigDecimal(input).setScale(2, BigDecimal.ROUND_HALF_UP);
                if (amountTendered.compareTo(totalAmount) < 0) {
                    JOptionPane.showMessageDialog(this,
                            "Amount is less than total. Please enter a valid amount.",
                            "Invalid Payment",
                            JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                break;
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this,
                        "Please enter a valid number.",
                        "Invalid Input",
                        JOptionPane.ERROR_MESSAGE);
            }
        }

        BigDecimal change = amountTendered.subtract(totalAmount);

        long transactionId = Database.saveTransaction(currentTransaction, totalAmount);
        if (transactionId != -1) {
            printReceipt(amountTendered, change);
            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            updateDisplay();

            if (transactionsDialog != null && transactionsDialog.isVisible()) {
                updateTransactionHistory();
            }
        }
    }
}