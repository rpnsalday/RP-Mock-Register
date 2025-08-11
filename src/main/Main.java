package main;

import javax.swing.SwingUtilities;

/**
 * Application entry point: launches the Swing UI on the EDT.
 */
public class Main {
    public static void main(String[] args) {
        // Redirect System.out/System.err to remote server if configured
        SocketConsoleRedirector.installIfConfigured();
        SwingUtilities.invokeLater(() -> new MockRegister());
    }
}