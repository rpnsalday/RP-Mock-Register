package main;

import java.sql.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class Database {
    private static final String DB_URL = "jdbc:h2:./mock_register_db;AUTO_SERVER=TRUE";
    private static final String USER = "sa";
    private static final String PASS = "";
    private static Connection connection;

    public static synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(DB_URL, USER, PASS);
        }
        return connection;
    }

    public static void initializeDatabase() {
        try {
            Connection conn = getConnection();
            createTables(conn);
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createTables(Connection conn) throws SQLException {
        // Create price book table
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS price_book (
                upc VARCHAR(255) PRIMARY KEY,
                description VARCHAR(255),
                price DECIMAL(10,2)
            )
        """);

        // Create transactions table
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                transaction_date TIMESTAMP,
                total_amount DECIMAL(10,2)
            )
        """);

        // Create transaction items table
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS transaction_items (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                transaction_id BIGINT,
                upc VARCHAR(255),
                quantity INT,
                price DECIMAL(10,2),
                subtotal DECIMAL(10,2),
                FOREIGN KEY (transaction_id) REFERENCES transactions(id),
                FOREIGN KEY (upc) REFERENCES price_book(upc)
            )
        """);

        // Create held orders table
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS held_orders (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                hold_date TIMESTAMP,
                total_amount DECIMAL(10,2)
            )
        """);

        // Create held order items table
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS held_order_items (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                held_order_id BIGINT,
                upc VARCHAR(255),
                quantity INT,
                price DECIMAL(10,2),
                subtotal DECIMAL(10,2),
                FOREIGN KEY (held_order_id) REFERENCES held_orders(id),
                FOREIGN KEY (upc) REFERENCES price_book(upc)
            )
        """);
    }

    // Modify all other methods to use getConnection() instead of creating new connections
    public static void loadPriceBook(String filename) {
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            conn.setAutoCommit(false);
            try {
                // Check if price_book table is empty
                boolean isEmpty = true;
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM price_book");
                    if (rs.next()) {
                        isEmpty = rs.getInt(1) == 0;
                    }
                }

                // Only load data if the table is empty
                if (isEmpty) {
                    String sql = "INSERT INTO price_book (upc, description, price) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
                            String line;
                            int batchCount = 0;
                            while ((line = reader.readLine()) != null) {
                                if (line.trim().isEmpty()) continue;
                                
                                String[] parts = line.split("\t");
                                if (parts.length >= 3) {
                                    try {
                                        pstmt.setString(1, parts[0].trim());
                                        pstmt.setString(2, parts[1].trim());
                                        pstmt.setBigDecimal(3, new BigDecimal(parts[2].trim()));
                                        pstmt.addBatch();
                                        batchCount++;
                                        
                                        if (batchCount >= 100) {
                                            pstmt.executeBatch();
                                            batchCount = 0;
                                        }
                                    } catch (NumberFormatException e) {
                                        System.err.println("Invalid price format for UPC: " + parts[0]);
                                    }
                                }
                            }
                            if (batchCount > 0) {
                                pstmt.executeBatch();
                            }
                        }
                    }
                }
                conn.commit();
            } catch (SQLException | IOException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }

    public static long saveTransaction(HashMap<String, Integer> items, BigDecimal totalAmount) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // Insert transaction
            String transactionSql = "INSERT INTO transactions (transaction_date, total_amount) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(transactionSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                pstmt.setBigDecimal(2, totalAmount);
                pstmt.executeUpdate();

                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    long transactionId = rs.getLong(1);

                    // Insert transaction items
                    saveTransactionItems(conn, transactionId, items);

                    conn.commit();
                    return transactionId;
                }
            }
            conn.rollback();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static List<String> getTopSellingUPCs(int limit) {
        List<String> topUPCs = new ArrayList<>();
        String sql = """
            SELECT ti.upc
            FROM transaction_items ti
            GROUP BY ti.upc
            ORDER BY SUM(ti.quantity) DESC
            LIMIT ?
        """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    topUPCs.add(rs.getString("upc"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return topUPCs;
    }

    public static long holdOrder(HashMap<String, Integer> items, BigDecimal totalAmount) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            String sql = "INSERT INTO held_orders (hold_date, total_amount) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                pstmt.setBigDecimal(2, totalAmount);
                pstmt.executeUpdate();

                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    long holdId = rs.getLong(1);
                    saveHeldOrderItems(conn, holdId, items);
                    conn.commit();
                    return holdId;
                }
            }
            conn.rollback();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static HashMap<String, Integer> retrieveHeldOrder(long orderId) {
        HashMap<String, Integer> items = new HashMap<>();
        try (Connection conn = getConnection()) {
            String sql = "SELECT upc, quantity FROM held_order_items WHERE held_order_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, orderId);
                ResultSet rs = pstmt.executeQuery();

                while (rs.next()) {
                    items.put(rs.getString("upc"), rs.getInt("quantity"));
                }

                // Delete the held order after retrieval
                deleteHeldOrder(conn, orderId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    private static void saveTransactionItems(Connection conn, long transactionId, HashMap<String, Integer> items) throws SQLException {
        String sql = "INSERT INTO transaction_items (transaction_id, upc, quantity, price, subtotal) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (var entry : items.entrySet()) {
                String upc = entry.getKey();
                int quantity = entry.getValue();
                BigDecimal price = getPriceFromPriceBook(conn, upc);
                BigDecimal subtotal = price.multiply(new BigDecimal(quantity));

                pstmt.setLong(1, transactionId);
                pstmt.setString(2, upc);
                pstmt.setInt(3, quantity);
                pstmt.setBigDecimal(4, price);
                pstmt.setBigDecimal(5, subtotal);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private static void saveHeldOrderItems(Connection conn, long holdId, HashMap<String, Integer> items) throws SQLException {
        String sql = "INSERT INTO held_order_items (held_order_id, upc, quantity, price, subtotal) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (var entry : items.entrySet()) {
                String upc = entry.getKey();
                int quantity = entry.getValue();
                BigDecimal price = getPriceFromPriceBook(conn, upc);
                BigDecimal subtotal = price.multiply(new BigDecimal(quantity));

                pstmt.setLong(1, holdId);
                pstmt.setString(2, upc);
                pstmt.setInt(3, quantity);
                pstmt.setBigDecimal(4, price);
                pstmt.setBigDecimal(5, subtotal);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    private static BigDecimal getPriceFromPriceBook(Connection conn, String upc) throws SQLException {
        String sql = "SELECT price FROM price_book WHERE upc = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, upc);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getBigDecimal("price");
            }
        }
        return BigDecimal.ZERO;
    }

    private static void deleteHeldOrder(Connection conn, long orderId) throws SQLException {
        // Delete items first due to foreign key constraint
        conn.createStatement().execute("DELETE FROM held_order_items WHERE held_order_id = " + orderId);
        conn.createStatement().execute("DELETE FROM held_orders WHERE id = " + orderId);
    }

    public static Item getItem(String upc) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT * FROM price_book WHERE upc = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, upc);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return new Item(
                        rs.getString("upc"),
                        rs.getString("description"),
                        rs.getBigDecimal("price")
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Add a cleanup method to close the connection when the application exits
    public static void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

public static void printAllTables() {
    try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
        System.out.println("\n=== PRICE BOOK ===");
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM price_book ORDER BY upc");
            while (rs.next()) {
                System.out.printf("UPC: %s, Description: %s, Price: $%.2f%n",
                    rs.getString("upc"),
                    rs.getString("description"),
                    rs.getBigDecimal("price"));
            }
        }

        System.out.println("\n=== TRANSACTIONS ===");
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM transactions ORDER BY id");
            while (rs.next()) {
                System.out.printf("ID: %d, Date: %s, Total: $%.2f%n",
                    rs.getLong("id"),
                    rs.getTimestamp("transaction_date"),
                    rs.getBigDecimal("total_amount"));
            }
        }

        System.out.println("\n=== TRANSACTION ITEMS ===");
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM transaction_items ORDER BY transaction_id, id");
            while (rs.next()) {
                System.out.printf("ID: %d, Transaction ID: %d, UPC: %s, Quantity: %d, Price: $%.2f, Subtotal: $%.2f%n",
                    rs.getLong("id"),
                    rs.getLong("transaction_id"),
                    rs.getString("upc"),
                    rs.getInt("quantity"),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("subtotal"));
            }
        }

        System.out.println("\n=== HELD ORDERS ===");
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM held_orders ORDER BY id");
            while (rs.next()) {
                System.out.printf("ID: %d, Date: %s, Total: $%.2f%n",
                    rs.getLong("id"),
                    rs.getTimestamp("hold_date"),
                    rs.getBigDecimal("total_amount"));
            }
        }

        System.out.println("\n=== HELD ORDER ITEMS ===");
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT * FROM held_order_items ORDER BY held_order_id, id");
            while (rs.next()) {
                System.out.printf("ID: %d, Hold ID: %d, UPC: %s, Quantity: %d, Price: $%.2f, Subtotal: $%.2f%n",
                    rs.getLong("id"),
                    rs.getLong("held_order_id"),
                    rs.getString("upc"),
                    rs.getInt("quantity"),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("subtotal"));
            }
        }

        System.out.println("\n=== END OF DATABASE DUMP ===\n");
    } catch (SQLException e) {
        e.printStackTrace();
    }
}
public static String getTransactionHistory() {
    StringBuilder history = new StringBuilder();
    try (Connection conn = getConnection()) {
        String sql = """
            SELECT t.id, t.transaction_date, t.total_amount,
                   ti.quantity, ti.price, ti.subtotal, p.description
            FROM transactions t
            LEFT JOIN transaction_items ti ON t.id = ti.transaction_id
            LEFT JOIN price_book p ON ti.upc = p.upc
            ORDER BY t.transaction_date DESC, t.id DESC
        """;
        
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            
            long currentTransactionId = -1;
            while (rs.next()) {
                long transactionId = rs.getLong("id");
                
                if (currentTransactionId != transactionId) {
                    // Print transaction header
                    if (currentTransactionId != -1) {
                        history.append("\n").append("-".repeat(60)).append("\n\n");
                    }
                    history.append(String.format("Transaction #%d - %s - Total: $%.2f\n",
                        transactionId,
                        rs.getTimestamp("transaction_date"),
                        rs.getBigDecimal("total_amount")));
                    history.append("-".repeat(60)).append("\n");
                    history.append(String.format("%-8s %-30s %-10s %-10s\n",
                        "Qty", "Item", "Price", "Subtotal"));
                    history.append("-".repeat(60)).append("\n");
                    currentTransactionId = transactionId;
                }
                
                // Print item details
                history.append(String.format("%-8d %-30s $%-9.2f $%-9.2f\n",
                    rs.getInt("quantity"),
                    truncateString(rs.getString("description"), 30),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("subtotal")));
            }
        }
    } catch (SQLException e) {
        history.append("Error retrieving transaction history: ").append(e.getMessage());
        e.printStackTrace();
    }
    return history.toString();
}

private static String truncateString(String input, int maxLength) {
    if (input == null) return "";
    if (input.length() <= maxLength) return input;
    return input.substring(0, maxLength - 3) + "...";
}
}