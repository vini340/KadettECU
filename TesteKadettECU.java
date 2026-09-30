import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class TesteKadettECU {

    private static int falhas = 0;

    public static void main(String[] args) throws Exception {
        testarLinhaHex();
        testarAnalisador();
        testarControlador();

        if (falhas == 0) {
            System.out.println("\nTodos os testes passaram.");
        } else {
            System.out.println("\nTestes com falha: " + falhas);
            System.exit(1);
        }
    }

    private static void testarLinhaHex() {
        System.out.println("LinhaHex");

        LinhaHex hello = new LinhaHex(0, "Hello".getBytes());
        verificar("endereço com 8 dígitos", "00000000".equals(hello.getEndereco()));
        verificar("bytes em hexadecimal", "48 65 6C 6C 6F ".equals(hello.getHex()));
        verificar("coluna ASCII", "Hello".equals(hello.getAscii()));
        verificar("linha com 5 bytes não está completa", !hello.estaCompleta());

        byte[] dezesseis = new byte[16];
        for (int i = 0; i < 16; i++) {
            dezesseis[i] = (byte) (0x41 + i);
        }
        LinhaHex cheia = new LinhaHex(0xA0, dezesseis);
        verificar("endereço em hexadecimal maiúsculo", "000000A0".equals(cheia.getEndereco()));
        verificar("separador '|' depois do 8º byte",
                "41 42 43 44 45 46 47 48 | 49 4A 4B 4C 4D 4E 4F 50 ".equals(cheia.getHex()));
        verificar("linha com 16 bytes está completa", cheia.estaCompleta());

        LinhaHex naoImprimiveis = new LinhaHex(0, new byte[] {0x00, 0x41, 0x7F, (byte) 0xFF});
        verificar("bytes não imprimíveis viram '.'", ".A..".equals(naoImprimiveis.getAscii()));

        byte[] original = {0x41};
        LinhaHex protegida = new LinhaHex(0, original);
        original[0] = 0x5A;
        verificar("encapsulamento: mudar o array de fora não altera a linha",
                "A".equals(protegida.getAscii()));

        verificar("cabeçalho", LinhaHex.cabecalho().startsWith("ENDEREÇO")
                && LinhaHex.cabecalho().endsWith("ASCII"));

        verificar("recusa linha vazia", lancaArgumentoInvalido(0, new byte[0]));
        verificar("recusa mais de 16 bytes", lancaArgumentoInvalido(0, new byte[17]));
        verificar("recusa endereço negativo", lancaArgumentoInvalido(-1, new byte[1]));
    }

    private static void testarAnalisador() throws Exception {
        System.out.println("AnalisadorECU");

        ArquivoECU vinte = arquivoTemporario("ABCDEFGHIJKLMNOPQRST".getBytes());
        ResultadoAnalise r = new AnalisadorECU(vinte).analisar(0x41);
        verificar("acha o byte 'A' no endereço 0", r.getEnderecos().equals(Arrays.asList(0)));
        verificar("20 bytes viram 2 linhas", r.getLinhas().size() == 2);
        verificar("primeira linha completa", r.getLinhas().get(0).estaCompleta());
        verificar("última linha incompleta", !r.getLinhas().get(1).estaCompleta());
        verificar("quantidade de bytes", r.getQuantidadeBytes() == 20);

        ResultadoAnalise semAchar = new AnalisadorECU(vinte).analisar(0x5A);
        verificar("byte ausente: sem ocorrências", !semAchar.encontrouOcorrencias());

        ArquivoECU repetido = arquivoTemporario(new byte[] {1, 2, 1, 2, 1});
        verificar("várias ocorrências",
                new AnalisadorECU(repetido).analisar(1).getEnderecos().equals(Arrays.asList(0, 2, 4)));

        boolean somenteLeitura = false;
        try {
            r.getEnderecos().add(99);
        } catch (UnsupportedOperationException e) {
            somenteLeitura = true;
        }
        verificar("encapsulamento: a lista do resultado é somente leitura", somenteLeitura);

        boolean naoEncontrado = false;
        try {
            new ArquivoECU("nao_existe_mesmo.bin").lerBytes();
        } catch (FileNotFoundException e) {
            naoEncontrado = true;
        }
        verificar("arquivo inexistente gera FileNotFoundException", naoEncontrado);
    }

    private static void testarControlador() throws Exception {
        System.out.println("ControladorECU (polimorfismo)");

        ExibidorFalso primeiro = new ExibidorFalso();
        ExibidorFalso segundo = new ExibidorFalso();
        ControladorECU controlador = new ControladorECU();
        controlador.adicionarExibidor(primeiro);
        controlador.adicionarExibidor(segundo);

        ArquivoECU arquivo = arquivoTemporario("KADETT".getBytes());
        capturarConsole(() -> controlador.analisar(arquivo.getCaminho(), 0x4B));
        verificar("todos os exibidores recebem o resultado", primeiro.chamadas == 1 && segundo.chamadas == 1);
        verificar("o resultado chega com o valor procurado", primeiro.ultimo.getValorProcurado() == 0x4B);

        String saida = capturarConsole(() -> controlador.analisar("nao_existe_mesmo.bin", 0x4B));
        verificar("arquivo inexistente: mensagem no console", saida.contains("Arquivo não encontrado"));
        verificar("arquivo inexistente: ninguém é chamado", primeiro.chamadas == 1 && segundo.chamadas == 1);
    }


    private static class ExibidorFalso implements ExibidorResultado {
        int chamadas = 0;
        ResultadoAnalise ultimo;

        @Override
        public void exibir(ResultadoAnalise resultado) {
            chamadas++;
            ultimo = resultado;
        }
    }

    private static ArquivoECU arquivoTemporario(byte[] conteudo) throws Exception {
        Path tmp = Files.createTempFile("kadettecu", ".bin");
        Files.write(tmp, conteudo);
        tmp.toFile().deleteOnExit();
        return new ArquivoECU(tmp.toString());
    }

    private static String capturarConsole(Runnable acao) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, "UTF-8"));
        try {
            acao.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString("UTF-8");
    }

    private static boolean lancaArgumentoInvalido(int endereco, byte[] bytes) {
        try {
            new LinhaHex(endereco, bytes);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void verificar(String nome, boolean condicao) {
        System.out.println("  " + (condicao ? "OK     " : "FALHOU ") + nome);
        if (!condicao) {
            falhas++;
        }
    }
}
