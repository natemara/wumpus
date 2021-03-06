package edu.miamioh.cse283.htw;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * The CaveServer class takes the following command-line parameters:
 * <p/>
 * <Hostname of CaveSystemServer> <port number of CaveSystemServer> <port number of this CaveServer>
 * <p/>
 * E.g., "localhost 1234 2000"
 */
public class CaveServer {

	/**
	 * Port base for this cave server.
	 */
	protected int portBase;

	/**
	 * Socket for accepting connections from players.
	 */
	protected ServerSocket clientSocket;

	/**
	 * Proxy to the CaveSystemServer.
	 */
	protected CaveSystemServerProxy caveSystem;

	/**
	 * Random number generator (used to pick caves for players).
	 */
	protected Random rng;

	/**
	 * Rooms in this CaveServer.
	 */
	protected ArrayList<Room> rooms;

	/**
	 * Constructor.
	 */
	public CaveServer(CaveSystemServerProxy caveSystem, int portBase) {
		this.caveSystem = caveSystem;
		this.portBase = portBase;
		this.rng = new Random();

		// locations for hazards
		int[] hazards = Utils.nRand(8, 20);

		// construct the rooms:
		rooms = new ArrayList<Room>();
		for (int i = 0; i < 20; ++i) {
			int contents = Room.EMPTY;

			if (i == hazards[0]) {
				contents = Room.WUMPUS;
			} else if (i == hazards[1]) {
				contents = Room.HOLE;
			} else if (i == hazards[2]) {
				contents = Room.BATS;
			} else if (i == hazards[3]) {
				contents = Room.GOLD;
			} else if (i == hazards[4]) {
				contents = Room.GOLD;
			} else if (i == hazards[5]) {
				contents = Room.ARROWS;
			} else if (i == hazards[6]) {
				contents = Room.ARROWS;
			} else if (i == hazards[7]) {
				contents = Room.LADDER;
			}

			rooms.add(new Room(contents, this));
		}

		// connect them to each other:
		for (int i = 0; i < 20; ++i) {
			rooms.get(i).connectRoom(rooms.get((i + 1) % 20));
			rooms.get(i).connectRoom(rooms.get((i + 2) % 20));
		}

		// and give them random ids:
		HashSet<Integer> ids = new HashSet<Integer>();
		for (int i = 0; i < 20; ++i) {
			int r = rng.nextInt(100);
			while (ids.contains(r)) {
				r = rng.nextInt(100);
			}
			rooms.get(i).setIdNumber(r);
		}
	}

	public Room randomRoom() {
		int index = rng.nextInt(rooms.size());
		return rooms.get(index);
	}

	/**
	 * Returns the port number to use for accepting client connections.
	 */
	public int getClientPort() {
		return portBase;
	}

	/**
	 * Returns an initial room for a client.
	 */
	public synchronized Room getInitialRoom() {
		return rooms.get(rng.nextInt(rooms.size()));
	}

	/**
	 * This is the thread that handles a single client connection.
	 */
	public class ClientThread implements Runnable {
		/**
		 * This is our "client" (actually, a proxy to the network-connected client).
		 */
		protected ClientProxy client;

		/**
		 * Notification messages.
		 */
		protected ArrayList<String> notifications;

		/**
		 * Constructor.
		 */
		public ClientThread(ClientProxy client) {
			this.client = client;
			this.notifications = new ArrayList<String>();
		}

		/**
		 * Returns true if there are notifications that should be sent to this client.
		 */
		public synchronized boolean hasNotifications() {
			return !notifications.isEmpty();
		}

		/**
		 * Returns and resets notification messages.
		 */
		public synchronized ArrayList<String> getNotifications() {
			ArrayList<String> t = notifications;
			notifications = new ArrayList<String>();
			return t;
		}

		/**
		 * Play the game with this client.
		 */
		public void run() {
			try {
				// the first time a player connects, send a welcome message:
				ArrayList<String> welcome = new ArrayList<String>();
				welcome.add("Welcome!");
				client.sendNotifications(welcome);

				// Put the player in an initial room and send them their initial
				// sensory information:
				Room r = getInitialRoom();
				client.changeRoom(r);

				// while the player is alive, listen for commands from the player
				// and for activities elsewhere in the cave:
				try {
					while (true) {
						// poll, waiting for input from client or other notifications:
						while (!client.ready() && !hasNotifications() && client.isAlive()) {
							try {
								Thread.sleep(50);
							} catch (InterruptedException ex) {
							}
						}

						// if there are notifications, send them:
						if (hasNotifications()) {
							client.sendNotifications(getNotifications());
						}

						// if the player is dead, send the DIED message and break:
						if (!client.isAlive()) {
							break;
						}

						// if the player did something, respond to it:
						if (client.ready()) {
							String line = client.nextLine().trim();

							try {
								dispatch(line);
							} catch (ClientQuitException e) {
								break;
							}
						}
					}
				} finally {
					// make sure the client leaves whichever room they're in,
					// and close the client's socket: 
					client.close();
				}
			} catch (Exception ex) {
				// If an exception is thrown, we can't fix it here -- Crash.
				ex.printStackTrace();
				System.exit(1);
			}
		}

		private void dispatch(String line) throws ClientQuitException {
			Matcher action = Protocol.ACTION_PATTERN.matcher(line);

			if (action.matches()) {
				int actionNumber = Integer.parseInt(action.group(1));
				int roomNumber;
				Room tempRoom;

				switch (actionNumber) {
					case Protocol.MOVE:
						// move the player: split out the room number, move the player, etc.
						// client has to leave the room: r.leaveRoom(client)
						// and enter the new room: newRoom.enterRoom(client)
						// send the client new senses here: client.sendSenses(r.getSensed());

						roomNumber = Integer.parseInt(action.group(3));
						tempRoom = client.getCurrentRoom().getRoom(roomNumber);
						if (tempRoom != null) {
							client.changeRoom(tempRoom);
						} else {
							notifications.add("Invalid room number.");
						}
						break;
					case Protocol.CLIMB:
						// climb the ladder, if the player is in a room with a ladder.
						// send a notification telling the player his score
						// and some kind of congratulations, and then kill
						// the player to end the game -- call kill(), above.

						tempRoom = client.getCurrentRoom();
						if (tempRoom.contents == Room.LADDER) {
							tempRoom.leaveRoom(client);
							String score = String.format("You fought well adventurer, and you earned %d gold!", client.getGold());
							client.sendNotifications(score);
							client.kill();
						} else {
							client.sendNotifications("There is no ladder in this room.");
						}

						break;
					case Protocol.SHOOT:
						// shoot an arrow: split out the room number into which the arrow
						// is to be shot, and then send an arrow into the right series of
						// rooms.

						roomNumber = Integer.parseInt(action.group(3));
						tempRoom = client.getCurrentRoom().getRoom(roomNumber);
						tempRoom.shoot(client);
						break;
					case Protocol.PICKUP:
						// pickup gold / arrows.
						tempRoom = client.getCurrentRoom();
						tempRoom.pickup(client);

						break;
				}
			} else if (line.startsWith(Protocol.QUIT)) {
				// no response: drop gold and arrows, and break.
				client.kill();
				throw new ClientQuitException();
			} else {
				// invalid response; send the client some kind of error message
				// (as a notificiation).
				client.sendNotifications("ERROR Malformed request");
			}
		}
	}

	/**
	 * Runs the CaveSystemServer.
	 */
	public void run() {
		try {
			// first thing we need to do is register this CaveServer
			// with the CaveSystemServer:
			clientSocket = new ServerSocket(getClientPort());
			caveSystem.register(clientSocket);
			System.out.println("CaveServer registered");

			// then, loop forever accepting Client connections:
			while (true) {
				ClientProxy client = new ClientProxy(clientSocket.accept());
				System.out.println("Client connected");
				(new Thread(new ClientThread(client))).start();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Main method (run the CaveServer).
	 */
	public static void main(String[] args) {
		try {
			InetAddress addr = InetAddress.getByName("localhost");
			int cssPortBase = 1234;
			int cavePortBase = 2000;
			InetAddress localhost = InetAddress.getByName("localhost");

			if (args.length > 0) {
				addr = InetAddress.getByName(args[0]);
				cssPortBase = Integer.parseInt(args[1]);
				cavePortBase = Integer.parseInt(args[2]);
				localhost = InetAddress.getByName(args[3]);
			}

			// first, we need our proxy object to the CaveSystemServer:
			CaveSystemServerProxy caveSystem = new CaveSystemServerProxy(new Socket(addr, cssPortBase + 1), localhost);

			// now construct this cave server, and run it:
			CaveServer cs = new CaveServer(caveSystem, cavePortBase);
			cs.run();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	/**
	 * Returns the Room with the given ID number, or NULL if one doesn't exist
	 *
	 * @param index the ID number to search for
	 * @return Room with the given ID number, or NULL if one doesn't exist
	 */
	private Room getRoomByNumber(int index) {
		for (int i = 0; i < rooms.size(); i++) {
			Room r = rooms.get(i);
			if (r.getIdNumber() == index) {
				return r;
			}
		}
		return null;
	}
}
