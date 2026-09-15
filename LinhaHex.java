public class LinhaHex {

    String endereco = "";
    String bau = "";
    String trunk = "";

        public LinhaHex(String endereco, String bau, String trunk){
            this.endereco = endereco;
            this.bau = bau;
            this.trunk = trunk;
        }

        public String formatarLinha(){
          return String.format("%-10s %-50s %s", endereco, bau, trunk);
        }
        public static void main(String[] args) {
            LinhaHex linha = new LinhaHex("00000000", "48 65 6C 6C 6F", "Hello");
            LinhaHex linha2 = new LinhaHex("0000002", "41 42 43", "ABC");
            System.out.println(linha.formatarLinha());
            System.out.println(linha2.formatarLinha());
        }
        public static String cabecalho(){
            return String.format("%-10s %-50s %s", "ENDEREÇO", "HEX", "ASCII");
        }
}
