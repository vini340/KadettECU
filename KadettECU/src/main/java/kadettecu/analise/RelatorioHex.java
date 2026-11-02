package kadettecu.analise;

/**
 * Monta o texto do relatório de busca (ocorrências + HEX viewer). Esse código estava dentro de
 * JanelaPrincipal.montarRelatorio; foi apenas movido para cá, sem mudar nada do que ele escreve,
 * para que a janela antiga e a aba "BINÁRIO / HEX" da nova interface usem o mesmo relatório.
 */
public final class RelatorioHex {

    private RelatorioHex() {
    }

    public static String montar(ResultadoAnalise resultado) {
        StringBuilder texto = new StringBuilder();
        texto.append("========================================\n");
        texto.append("           RESULTADO DA BUSCA\n");
        texto.append("========================================\n\n");

        if (resultado.encontrouOcorrencias()) {
            texto.append(String.format("%-12s %-8s %s%n", "ENDEREÇO", "HEX", "ASCII"));
            for (int endereco : resultado.getEnderecos()) {
                texto.append(String.format("%-12d %-8s %c%n",
                        endereco,
                        String.format("%02X", resultado.getValorProcurado()),
                        (char) resultado.getValorProcurado()));
            }
        } else {
            texto.append("Nenhuma ocorrência encontrada.\n\n");
        }

        texto.append("========================================\n");
        texto.append("              HEX VIEWER\n");
        texto.append("========================================\n");
        texto.append(LinhaHex.cabecalho()).append("\n");
        for (LinhaHex linha : resultado.getLinhas()) {
            texto.append(linha.formatarLinha()).append("\n");
        }
        return texto.toString();
    }
}
