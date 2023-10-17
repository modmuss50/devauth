package me.modmuss50.devauth;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

public class Main {
	private static final String DEV_AUTH_MAVEN_ARTIFACT = "me.modmuss50:devauth:unspecified";
	private static final String DLI_CLASS_NAME = "net.fabricmc.devlaunchinjector.Main";
	private static final Path SHARED_PATH = Path.of(System.getProperty("java.io.tmpdir"), "/devauth-ipc");
	private static final String COMMAND_ARGUMENTS = "arguments";
	private static final boolean REQUIRES_VALIDATION = false;

	public static void main(String[] args) throws Throwable {
		// If the DLI config is set, we are running in a dev environment
		if (System.getProperty("fabric.dli.config") != null) {
			devMain(args);
			return;
		}

		// If the accessToken argument is set, we are running in the launcher
		for (String arg : args) {
			if (arg.equals("--accessToken")) {
				launcherMain(args);
				return;
			}
		}

		// Otherwise, we are installing the dev auth profile
		installMain();
	}

	private static void launcherMain(String[] args) throws IOException {
		openWindowInNewThread(parseUsername(args));
		System.out.println("Starting IPC server");

		List<String> arguments = Arrays.stream(args).toList();

		Files.deleteIfExists(SHARED_PATH);

		// Open an IPC socket
		UnixDomainSocketAddress address = UnixDomainSocketAddress.of(SHARED_PATH);

		try (ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
			serverChannel.bind(address);

			while (true) {
				try (SocketChannel clientChannel = serverChannel.accept();
				     Scanner scanner = new Scanner(clientChannel, StandardCharsets.UTF_8)) {

					while (!Thread.currentThread().isInterrupted()) {
						if (scanner.hasNextLine()) {
							String line = scanner.nextLine();

							if (line.equals(COMMAND_ARGUMENTS)) {
								if (!REQUIRES_VALIDATION || validateRequest()) {
									clientChannel.write(StandardCharsets.UTF_8.encode(encodeListToString(arguments) + "\n"));
								}

								break;
							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static String parseUsername(String[] args) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--username")) {
				return args[i + 1];
			}
		}

		return null;
	}

	private static boolean validateRequest() {
		int choice = JOptionPane.showConfirmDialog(null, "Do you want to allow this authentication request?", "Minecraft Auth Request", JOptionPane.YES_NO_OPTION);
		return choice == JOptionPane.YES_OPTION;
	}

	// Display a swing window with an exit button, and wait for close
	private static void displayWindow(String username) {
		JFrame frame = new JFrame("Minecraft Auth Request");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(300, 300);
		frame.setVisible(true);

		JLabel label = new JLabel("Dev auth server, close window to exit");
		label.setBounds(0, 0, 300, 300);
		frame.add(label);

		while (frame.isVisible()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		System.exit(0);
	}

	private static void openWindowInNewThread(String username) {
		new Thread(() -> displayWindow(username)).start();
	}

	private static void devMain(String[] args) throws Throwable {
		List<String> arguments = new ArrayList<>(Arrays.stream(args).toList());

		// Connect to the shared IPC server and request the arguments
		UnixDomainSocketAddress address = UnixDomainSocketAddress.of(SHARED_PATH);
		try (SocketChannel socketChannel = SocketChannel.open(address);
		     Scanner scanner = new Scanner(socketChannel, StandardCharsets.UTF_8)) {
			// Request the arguments
			socketChannel.write(StandardCharsets.UTF_8.encode(COMMAND_ARGUMENTS + "\n"));

			System.out.println("Waiting for auth response");

			if (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				arguments.addAll(decodeListFromString(line));
			}
		}

		MethodHandle handle = MethodHandles.publicLookup().findStatic(Class.forName(DLI_CLASS_NAME), "main", MethodType.methodType(void.class, String[].class));
		handle.invokeExact((String[]) arguments.toArray(String[]::new));
	}

	private static void installMain() throws IOException {
		Path dotMinecraftDir = getDotMinecraftDir();
		int choice = JOptionPane.showConfirmDialog(null, "Do you want to install the dev auth profile?\nTo: " + dotMinecraftDir.toAbsolutePath().toString(), "Dev Auth", JOptionPane.YES_NO_OPTION);

		if (choice != JOptionPane.YES_OPTION) {
			System.exit(0);
		}

		System.out.println("Installing dev launch injector");

		Path librariesDir = dotMinecraftDir.resolve("libraries").resolve(DEV_AUTH_MAVEN_ARTIFACT.replace('.', '/').replace(':', '/'));
		Path jarPath = librariesDir.resolve(DEV_AUTH_MAVEN_ARTIFACT.split(":")[1] + "-unspecified.jar");
		Files.createDirectories(librariesDir);

		try {
			Files.deleteIfExists(jarPath);
			Files.copy(Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()), jarPath);
		} catch (Exception e) {
			throw new RuntimeException("Failed to copy jar to " + jarPath, e);
		}

		System.out.println("Creating launcher version json");
		Path versionDir = dotMinecraftDir.resolve("versions").resolve("devauth");
		Path versionJsonPath = versionDir.resolve("devauth.json");
		Files.createDirectories(versionDir);

		String currentTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

		Files.writeString(versionJsonPath, readLauncherProfileFromResources());

		JOptionPane.showMessageDialog(null, "Dev auth profile installed, you can now select it in the launcher", "Dev Auth", JOptionPane.INFORMATION_MESSAGE);
		System.exit(0);
	}

	private static String readLauncherProfileFromResources() {
		try (InputStream stream = Main.class.getResourceAsStream("/launcher_profile.json")) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path getDotMinecraftDir() {
		String os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win")) {
			return Path.of(System.getenv("APPDATA"), ".minecraft");
		} else if (os.contains("mac")) {
			return Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft");
		} else {
			return Path.of(System.getProperty("user.home"), ".minecraft");
		}
	}

	private static String encodeListToString(List<String> list) {
		final StringBuilder builder = new StringBuilder();

		for (String string : list) {
			builder.append(base64Encode(string)).append(",");
		}

		return builder.toString();
	}

	private static List<String> decodeListFromString(String string) {
		final List<String> list = new ArrayList<>();

		for (String s : string.split(",")) {
			list.add(base64Decode(s));
		}

		return list;
	}

	private static String base64Encode(String string) {
		return new String(Base64.getEncoder().encode(string.getBytes()));
	}

	private static String base64Decode(String string) {
		return new String(Base64.getDecoder().decode(string.getBytes()));
	}
}
