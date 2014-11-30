package ru.hwsec.teamara;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Calendar;
import java.util.Random;
import java.util.Scanner;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;


public class AraTerminal {

	protected byte[] cardKeyBytes;
    private byte[] cardEncKey;
    private byte[] cardMacKey;
    private byte[] cardIV;
    private byte[] terminalEncKey;
    private byte[] terminalMacKey;
    private byte[] terminalIV;
    
    // This variable save the ID of the terminal.
    protected byte termID;
    
    // This is the monthly allowance defined in the design document.
    final static short MONTHLY_ALLOWANCE = 200;
    	
	protected final boolean debug = true;
	
	/*
	 * Log raw structure in the smart card:
	 *             [ termID |  Date   | Balance | Term Sig | Card Sig ]
	 * Bytes:          1        19         2         56        56
	 * Starting Pos:   0         1        20         22        78
	 */

	// Maximum number of log.
	final protected int MAX_LOGS  = 5;
	/* ---     Sizes      --- */
	// The length of the date field.
	final protected int DATE_SIZE = 19;
	// The length of each signature.
	final protected int SIG_SIZE  = 56;
	/* ---   Positions    --- */
	// Starting position of the date field.
	final protected int DATE_POS  = 1;
	// Starting position of the balance field.
	final protected int BALANCE_POS  = DATE_POS + DATE_SIZE;
	// Starting position of the terminal signature field.
	final protected int TERM_SIG_POS = BALANCE_POS + 2;         // 22
	// Starting position of the card signature field.
	final protected int CARD_SIG_POS = TERM_SIG_POS + SIG_SIZE; // 78
	// The constant byte size of each log entry.
	final protected int LOG_SIZE  = CARD_SIG_POS + SIG_SIZE;    // 134
	// The buffer of the UPDATE_BALANCE_CHARGE command includes:
	// [ termID |  Date   | Balance ]
	final protected int UPDATE_BALANCE_CHARGE_LENGTH = TERM_SIG_POS;
	// The buffer of the UPDATE_BALANCE_PETROL command includes:
	// [ termID |  Date   | Balance | Term Sig ]
	final protected int UPDATE_BALANCE_PETROL_LENGTH = CARD_SIG_POS;
	
	
    protected CardComm cardComm;

    /*
     * Constructor, gets the termID as an argument.
     * TODO, set the public/private key also.
     */
    public AraTerminal(byte b){
    	this.termID = b;
    	try {
			cardComm = new CardComm();
		} catch (CardException e) {
			System.out.println("Could not connect to the card or simulator.");
			System.exit(1);
		}
		System.out.println("Connected to the smart card.");
    }
    
    protected void execute() {
        /* INITIALISATION STATE */
        /*
        this.setPIN();
        this.pinCheck();
        this.pinCheck();
        */

        /* Issued State */
        try {
			this.performHandshake();
			this.pinCheck();
			this.pinCheck();
		} catch (CardException e) {
			System.out.println("Could not perform the handshake with the card.");
		} catch (GeneralSecurityException e) {
			System.out.println("There was a crypto problem on the terminal side.");
		} /*catch (Exception e){
			System.out.println(e.getMessage());
		}*/
    }

    /* Mutual Authentication Functions */

    private void performHandshake() throws CardException, GeneralSecurityException {
        ResponseAPDU resp;
        // We first generate 4 random bytes to send the card
        Random rnd = new Random(Calendar.getInstance().getTimeInMillis());
        byte[] termRndBytes = new byte[4];
        rnd.nextBytes(termRndBytes);

        // Send TERMINAL_HELLO and get back the CARD_HELLO answer containing 4 random bytes
        resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.TERMINAL_HELLO, 0, 0, termRndBytes));
        byte[] cardRndBytes = resp.getData();

        // Send the public key of the terminal with the signature
        // 51 bytes (0..50) public key of the terminal
        // 54 bytes (51..104) signature of the key
        // Total: 105 bytes
        byte[] signedKey = new byte[105];
        System.arraycopy(ECCTerminal.PUBLIC_KEY_BYTES, 0, signedKey, 0, ECCTerminal.PUBLIC_KEY_BYTES.length);
        System.arraycopy(ECCTerminal.SIGNATURE_BYTES, 0, signedKey, ECCTerminal.PUBLIC_KEY_BYTES.length, ECCTerminal.SIGNATURE_BYTES.length);
        // P1 specifies what type of terminal this is:
        // P1 = 1 ==> charging terminal
        // P1 = 2 ==> pump terminal
        resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.TERMINAL_KEY, 1, 0, signedKey));
        byte[] data = resp.getData();

        // Verify the public key and signature received from the card
        cardKeyBytes = new byte[51];
        byte[] cardSignatureBytes = new byte[54];
        System.arraycopy(data, 0, cardKeyBytes, 0, 51);
        System.arraycopy(data, 51, cardSignatureBytes, 0, 54);
        try {
			ECCTerminal.verifyCardKey(cardKeyBytes, cardSignatureBytes);
		} catch (GeneralSecurityException e) {
			System.out.println("An error occured while verifying the card key.");
		}
		
		// Generate DH Secret
    	byte[] terminalSecret = ECCTerminal.performDH(cardKeyBytes);
    	MessageDigest md = MessageDigest.getInstance("SHA");
    	md.update(terminalSecret);
    	setKeys(termRndBytes, cardRndBytes, md.digest());        
        
    	byte[] payload = SymTerminal.encrypt(new byte[]{0x01, 0x02, 0x03, 0x04});
    	resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.CHANGE_CIPHER_SPEC, 1, 0, payload));
        byte[] ctext = resp.getData();
        byte[] ptext = SymTerminal.decrypt(ctext);
        
        
    }

    public void setKeys(byte[] termRndBytes, byte[] cardRndBytes, byte[] terminalSecret) throws CardException, GeneralSecurityException {
    	this.cardEncKey = new byte[16];
    	this.cardMacKey = new byte[16];
    	this.cardIV = new byte[16];
    	this.terminalEncKey = new byte[16];
    	this.terminalMacKey = new byte[16];
    	this.terminalIV = new byte[16];
    	
    	// Temp arrays
    	byte[] hashInput = new byte[34];	
    	byte[] hashOut = new byte[20];
    	
    	System.arraycopy(termRndBytes, 0, hashInput, 0, 4);
    	System.arraycopy(cardRndBytes, 0, hashInput, 4, 4);
    	System.arraycopy(terminalSecret, 0, hashInput, 8, 20);

    	// cardEncKey
    	hashInput[33] = (byte) 0x00;
    	MessageDigest md = MessageDigest.getInstance("SHA");
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.cardEncKey, 0, 16);
    	
    	// cardMacKey
    	hashInput[33] = (byte) 0x01;
    	md.reset();
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.cardMacKey, 0, 16);
    	
    	// cardIV
    	hashInput[33] = (byte) 0x02;
    	md.reset();
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.cardIV, 0, 16);
    	
    	// terminalEncKey
    	hashInput[33] = (byte) 0xA0;
    	md.reset();
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.terminalEncKey, 0, 16);
    	
    	// terminalMACKey
    	hashInput[33] = (byte) 0xA1;
    	md.reset();
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.terminalMacKey, 0, 16);
    	
    	// terminalIV
    	hashInput[33] = (byte) 0xA2;
    	md.reset();
    	md.update(hashInput);
    	hashOut = md.digest();    	
    	System.arraycopy(hashOut, 0, this.terminalIV, 0, 16);
    	
    	SymTerminal.init(terminalIV, cardIV, terminalEncKey, cardEncKey, terminalMacKey, cardMacKey);
    }
    
    /* PIN Functions */

    public void setPIN() throws CardException {
    	System.out.print("INITIALISATION: Please choose a strong PIN Code");
    	byte pincode[] = ask_for_PIN();

        ResponseAPDU resp;
        resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.SET_PIN, 0, 0, pincode));
        /*
        byte[] cardRndBytes = resp.getData();
        System.out.println(cardRndBytes.length);

        for (byte b :  cardRndBytes)
        	System.out.format("0x%x ", b);
        System.out.println();
        */
    }

    public boolean pinCheck() throws CardException, GeneralSecurityException {
    	byte pincode[] = ask_for_PIN();
    	pincode = SymTerminal.encrypt(pincode);
        ResponseAPDU resp;
        // Send TERMINAL_HELLO and get back the CARD_HELLO answer containing 4 random bytes
        resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.CHECK_PIN, 0, 0, pincode));
        byte[] cardRndBytes = resp.getData();
        /*System.out.println(cardRndBytes.length);
        for (byte b :  cardRndBytes)
        	System.out.format("0x%x ", b);
        System.out.println();*/
        if(cardRndBytes[0] == (byte) 0x01)
        		System.out.println("Correct PIN");
        else{
        		System.out.println("Wrong PIN");
        		System.out.printf("Tries Remaining: ");
        		System.out.println(cardRndBytes[1]);
        }
        return true;
    }


    // Asks user for PIN and returns the byte array.
    private byte [] ask_for_PIN(){
    	String input = null;
    	byte[] bytes = ByteBuffer.allocate(4).putInt(1111).array(); // initialize

        System.out.println("");
        System.out.print("Enter PIN: ");
        Scanner in = new Scanner( System.in );

        try{
        	input = in.next();
        	int pin = Integer.parseInt(input); // Just to check if it is an integer, var is not used.


        	int i = 0;
        	for(char charUserOutput : String.valueOf(input).toCharArray())
        	{
            	 bytes[i] = (byte) charUserOutput;
            	 i++;
        	}
        	/*
            for (byte b : bytes)
            {
            	System.out.format("0x%x ", b);
            }
            System.out.println();
            */

        } catch(NumberFormatException e) {
        	System.out.println("Input is not a number");
        	System.exit(1);
        } catch (Exception e) {
            System.out.println("IO error.");
            System.exit(1);
        }
        return bytes;
    }
    
    /*
     * Return the date in mysql DATETIME format.
     */
    protected String get_date(){
    	DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    	Date date = new Date();
    	//System.out.println(dateFormat.format(date)); //2014/08/06 15:59:48
    	return dateFormat.format(date);
    }
    
    /**
     * Send instruction START_PUMPING to the card and
     * retrieve the balance. 
     * ATM. It is used for both Charging and terminal.
     */
    protected short getBalance(){
        ResponseAPDU resp;
        short balance = 0;
        try {
        	resp = this.cardComm.sendToCard(new CommandAPDU(0, Instruction.GET_BALANCE, 1, 0));
			byte[] temp = resp.getData();
			balance = (short) ((temp[1] << 8) + (temp[0]&0xFF));
            if ( debug == true){
            	System.out.println("Getting balance..");
            	for (byte b :  temp)
            		System.out.format("0x%x ", b);
            	System.out.println();
            	System.out.println("Balance is: " + balance);
            }
            System.out.println("Your current balance is: " + balance);
			if (balance < 0)
				throw new IllegalStateException("Balance cannot be negative");
        	return balance;
        } catch (IllegalStateException ex) {
			System.out.println(ex.getMessage());
    		System.out.println("Card is corrupted.");
			System.exit(1);
		} catch (CardException ex) {
			System.out.println(ex.getMessage());
			System.out.println("Getting logs failed.");
			System.exit(1);
		}
    	return balance;
    }

    public static void main(String[] arg) throws CardException {
    	AraTerminal araTerminal = new AraTerminal((byte) 0x01);
    	araTerminal.execute();
    	try{
    		araTerminal.cardComm.card.disconnect(false);
    	}catch (CardException e) {
    		System.out.println("Could not close card connection.");
    	}
    }
}
