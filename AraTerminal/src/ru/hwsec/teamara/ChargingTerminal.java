package ru.hwsec.teamara;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;



public class ChargingTerminal extends AraTerminal {

	/*
	 * Class used to represent the card that it is used at the moment.
	 */
	public class Card {
		protected int cardID;
		protected short balance;
		public Card(){
			
		}
		public Card(int cardID, short balance) {
			this.cardID = cardID;
			this.balance = balance;
		}
	}
	
	private final boolean debug = true;
	
	/* Charging terminal is online and has access to the database. */
	private MySql db;
	
	/*
	 * Log raw structure in the smart card:
	 *             [ termID |  Balance | Date | Term Sig | Card Sig ]
	 * Bytes:          1         2        18       54        54
	 * Starting Pos:   0         1         3       21        75
	 */
	// The constant byte size of each log entry.
	final int LOG_SIZE  = 129;
	
	// Maximum number of log.
	final int MAX_LOGS  = 5;
	
	// The length of the date field.
	final int DATE_SIZE = 18;
	// The length of each signature.
	final int SIG_SIZE  = 54;
	
	// Starting position of the date field.
	final int DATE_POS  = 3;
	// Starting position of the terminal signature field.
	final int TERM_SIG_POS = 21;
	// Starting position of the card signature field.
	final int CARD_SIG_POS = 74;
	

	public ChargingTerminal(MySql tdb, byte ttermID){
        super(ttermID); // set the terminal ID.
        this.db = tdb;
    }

	/**
     * Get card logs adn store them in the database.
     * 
     * Additionally,    TODO
     * Check if the card logs fulfil specified criteria
     *  - less than 200 litres per month
     *  - Only 5 withdrawals
	 * @param card obj Card with the details of the connected card.
	 * @return true/false depending on success.
	 */
	private boolean getLogs(Card card) {
        ResponseAPDU resp;
    	boolean status = true;
    	
    	// The buffer that temporary stores the logs. 
    	byte[] buffer = new byte[MAX_LOGS*LOG_SIZE];
    	Arrays.fill( buffer, (byte) 0x00 );

    	// Size of the buffer that contains usefull information.
    	int buffer_size = 0;
    	
        try {
        	resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.GET_LOGS, 1, 0));
			byte[] temp = resp.getData();
			buffer_size = temp.length;
            if ( debug == true){
            	System.out.println("In function getLogs..");
            	for (byte b :  temp)
            		System.out.format("0x%x ", b);
            	System.out.println();
            }
			if (temp[0] == (byte) 0xFF) // Signal that defines that card logs are empty.
				return true;
			// copy bytes to the buffer.
			System.arraycopy(temp, 0, buffer, 0, buffer_size);			
		} catch (CardException ex) {
			System.out.println(ex.getMessage());
			System.out.println("Getting logs failed.");
			System.exit(1);
		}

		int number_of_logs = 0;
		try {
			number_of_logs = (buffer_size + 1) / LOG_SIZE;
			if (number_of_logs > 5) // Maximum log capacity is 5.
				throw new IllegalStateException("Cannot handle " + number_of_logs + " logs.");
        } catch(NumberFormatException ex) {
        	System.out.print(ex.getMessage());
		} catch (IllegalStateException ex) {
			System.out.println(ex.getMessage());
		}
		
		// Decode each log and store it in the database.
		for ( int i = 0; i < number_of_logs; i++){
			int index = 0 + i * LOG_SIZE; // Starting index of each log.
			
			int ttermID = (int) buffer[index];
			short balance = (short) (buffer[1] | (buffer[2] << 8 ));
			short transaction = (short) 1; // transaction must be calculated in a later version.
			byte date_bytes[] = new byte[DATE_SIZE]; 
			byte sig_term_bytes[] = new byte[SIG_SIZE];
			byte sig_card_bytes[] = new byte[SIG_SIZE];
			// copy bytes to the buffer.
			System.arraycopy(buffer, index + DATE_POS,     date_bytes,     0, DATE_SIZE);			
			System.arraycopy(buffer, index + TERM_SIG_POS, sig_term_bytes, 0, SIG_SIZE);
			System.arraycopy(buffer, index + CARD_SIG_POS, sig_card_bytes, 0, SIG_SIZE);
			
			String date     = new sun.misc.BASE64Encoder().encode(date_bytes);
			String sig_term = new sun.misc.BASE64Encoder().encode(sig_term_bytes);
			String sig_card = new sun.misc.BASE64Encoder().encode(sig_card_bytes);
			
			status = db.addlog(card.cardID, balance, transaction, ttermID, date, sig_card, sig_term);
	    	if (!status){
	    		System.out.println("Storing logs failed.");
	    		System.exit(1);
	    	}
		}
        return true;
    }
	
	/**
	 * Just send command CLEAR LOGS to the smartcard.
	 */
	private boolean clearLogs(){
		try {
			this.cardComm.sendToCard(new CommandAPDU(0, Instruction.CLEAR_LOGS, 1, 0));
			return true;
		} catch (CardException ex) {
			System.out.println(ex.getMessage());
			return false;
		}
	}

	/**
	 * Compare the cardID's balance at the smart card and at the database.
	 */
	private boolean verifyBalance(Card card){
		int tbalance = db.get_balance(card.cardID);
		if ( tbalance == card.balance)
			return true;
		else if (tbalance == -1)
			System.out.println("cardID: " + card.cardID + " has not been found.");
		else if (tbalance == -2)
			System.out.println("SQL error");		
		return false;
	}
	
    /* Revoke the card if backend database has revoke flag set for that card*/
    private boolean revoke(){
    	return true;
    }

    /*
     * Perform an atomic operation. TODO!!!
     * - Sign the message
     * - Send message and new balance to the smart card
     * - Get the signature
     * - Store the message and the signatures to the database
     */
    private boolean updateBalance(Card card, short new_balance, String msg){
    	// Send msg in bytes to the smart card. 
    	byte[] msg_bytes = new byte[18]; // Static.
    	
		try {
			// Create full msg in bytes
			byte[] temp = new sun.misc.BASE64Decoder().decodeBuffer(msg);
			System.arraycopy(temp, 0, msg_bytes, 0, temp.length);
			msg_bytes[temp.length  ] = (byte) (new_balance    & 0xFF);
			msg_bytes[temp.length+1] = (byte) (new_balance>>8 & 0xFF);
			
            if ( debug == true){
            	System.out.println("In function updateBalance..");
            	System.out.println(msg + Short.toString(new_balance));
            	for (int i = 0; i < temp.length+2; i++){
            		System.out.format("0x%x ", msg_bytes[i]);
            	}
            	System.out.println();
            }
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		// Create signature.
    	byte[] sig_term_bytes = new byte[SIG_SIZE];
		try {
			sig_term_bytes = ECCTerminal.performSignature(msg_bytes);
            if ( debug == true){
            	System.out.println("In function updateBalance..");
            	System.out.println("Signature from card, length: " + sig_term_bytes.length);
                for (byte b :  sig_term_bytes)
                	System.out.format("0x%x ", b);
                System.out.println();
            }
		} catch (GeneralSecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	String sig_term = new sun.misc.BASE64Encoder().encode(sig_term_bytes);
    	
		
    	// Send new balance and msg to the smart card and get the signature.
    	byte[] sig_card_bytes = new byte[SIG_SIZE];
        ResponseAPDU resp;
        try {
        	resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.UPDATE_BALANCE_CHARGE, 0, 0, msg_bytes));
        	sig_card_bytes = resp.getData();

            if ( debug == true){
            	System.out.println("Reply for UPDATE_BALANCE_CHARGE, the signature of smartcard is:");
            	for (byte b :  sig_card_bytes)
            		System.out.format("0x%x ", b);
            	System.out.println();
            	System.exit(1);
            }

		} catch (CardException ex) {
			System.out.println(ex.getMessage());
			System.out.println("Getting logs failed.");
			System.exit(1);
		}
		String sig_card = new sun.misc.BASE64Encoder().encode(sig_card_bytes);
    	
    	// Verify signature of smart card.
    	// TODO convert msg to bytes
    	// ECCTerminal.performSignatureVerification(msg, sig_card_bytes, this.cardKeyBytes)
    	
    	// Save log entry to the database.
    	db.addlog(card.cardID, card.balance, this.MONTHLY_ALLOWANCE, (int) this.termID, this.get_date(), sig_card, sig_term);
    	
    	// Update balance in table sara_card
    	db.updateBalance(card.cardID, new_balance);
    	
    	return true;
    }
    
    void use (){
    	System.out.println("Welcome to charging terminal.");
    	
    	// exit if it turns false. 
    	boolean status = true;
    	// object that describes the connected card. 
    	Card card = new Card( (int) 0xA1, (short) 100 );  
    	
    	// retrieve and store logs as well as get the basic info of the card.
    	//status = getLogs(card);

    	// if getting logs was successful, inform smart card to clear the entries. 
    	// Not needed. See note below above the function
    	//status = clearLogs();
    	
    	// verify that the balance in the smart card
    	// matches the balance in the database.
    	//status = verifyBalance(card);
    	if (!status){
    		System.out.println("Card is corrupted.");
    		// REVOKE CARD.
    		revoke();
    		System.exit(1);
    	}    	
    	
    	// calculate new balance. 
    	short new_balance = (short) (card.balance + this.MONTHLY_ALLOWANCE); 
    	
    	// construct the message that has to be signed by both the terminal and the smart card.
    	String stermID = new sun.misc.BASE64Encoder().encode(new byte[] { this.termID }); 
    	String msg = stermID + this.get_date();
    	
    	// perform an atomic operation of updating the 
    	// balance and storing the signatures to the database.
    	status = updateBalance(card, new_balance, msg);
    	if (!status){
    		System.out.println("Updating balance failed.");
    		System.exit(1);
    	}
    	else
    		System.out.println("Card is charged.");
    }
}