public class LinhaHex {

    public static final int BYTES_POR_LINHA = 16;
    private static final int BYTES_POR_GRUPO = 8;

    private final int endereco;
    private final byte[] bytes;

    public LinhaHex(int endereco, byte[] bytes) {
        if (endereco < 0) {
            throw new IllegalArgumentException("Endereço não pode ser negativo: " + endereco);
        }
        if (bytes == null || bytes.length == 0 || bytes.length > BYTES_POR_LINHA) {
            throw new IllegalArgumentException(
                    "Uma linha precisa ter de 1 a " + BYTES_POR_LINHA + " bytes");
        }
        this.endereco = endereco;
        this.bytes = bytes.clone(); 
    }

    public String getEndereco() {
        return String.format("%08X", endereco);
    }

    public String getHex() {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            hex.append(String.format("%02X", bytes[i] & 0xFF)).append(' ');
            if (i + 1 == BYTES_POR_GRUPO) {
                hex.append("| ");
            }
        }
        return hex.toString();
    }

    public String getAscii() {
        StringBuilder ascii = new StringBuilder();
        for (byte b : bytes) {
            int valor = b & 0xFF;
            ascii.append(valor >= 32 && valor <= 126 ? (char) valor : '.');
        }
        return ascii.toString();
    }

    public boolean estaCompleta() {
        return bytes.length == BYTES_POR_LINHA;
    }

    public String formatarLinha() {
        return String.format("%-10s %-50s %s", getEndereco(), getHex(), getAscii());
    }

    public static String cabecalho() {
        return String.format("%-10s %-50s %s", "ENDEREÇO", "HEX", "ASCII");
    }
}
