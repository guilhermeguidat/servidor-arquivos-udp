package udp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {

    private static final File PASTA = new File("arquivos");
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final InetSocketAddress ENDERECO = new InetSocketAddress("0.0.0.0", Protocolo.PORTA);

    private static DatagramSocket socket; //porta q chega os comandos
    private static int proximoId = 1; //contador para as operacoeds
    private static final Map<String, SocketAddress> clientes = new ConcurrentHashMap<>(); //clientes conectados
    private static final Set<SocketAddress> transferindo = ConcurrentHashMap.newKeySet(); //clientes com operacao em andamento

    //inicia o sv e fica em loop para receber e processar requisicoes
    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            Protocolo.perda = Double.parseDouble(args[0]);
        }
        PASTA.mkdirs();
        socket = new DatagramSocket(ENDERECO);
        log(ENDERECO, "INICIO", "OK", "servidor escutando na porta " + Protocolo.PORTA
                + " (perda simulada " + (int) (Protocolo.perda * 100) + "%)");

        while (true) {
            DatagramPacket pacote = new DatagramPacket(new byte[Protocolo.TAM_BUFFER], Protocolo.TAM_BUFFER);
            socket.receive(pacote);
            SocketAddress origem = pacote.getSocketAddress();
            if (Protocolo.descartar()) {
                log(origem, "PERDA", "AVISO", "pacote descartado (simulacao de perda)");
                continue;
            }
            try {
                tratar(Protocolo.texto(pacote).split("\\|"), origem);
            } catch (Exception e) {
                log(origem, "ERRO", "ERRO", e.toString());
                Protocolo.enviar(socket, "ERROR|500|erro no servidor", origem);
            }
        }
    }

    //ve o comando e encaminha para o processo certo
    private static void tratar(String[] campos, SocketAddress origem) throws IOException {
        switch (campos[0]) {
            case "REGISTER" -> { //salva o cliente
                clientes.put(campos[1], origem);
                Protocolo.enviar(socket, "ACK|REGISTER", origem);
                log(origem, "REGISTER", "OK", "cliente " + campos[1] + " conectado");
            }
            case "LIST" -> {
                Protocolo.enviar(socket, "LIST" + listaArquivos(), origem);
                log(origem, "LIST", "OK", "lista enviada");
            }
            case "UPLOAD" -> iniciarUpload(campos, origem);
            case "DOWNLOAD" -> iniciarDownload(campos[1], origem);
            default -> {
                Protocolo.enviar(socket, "ERROR|400|comando desconhecido", origem);
                log(origem, campos[0], "ERRO", "comando desconhecido");
            }
        }
    }

    //cria thred para receber o arquivo, tb cria id para a operacao e ignora repetidas
    private static void iniciarUpload(String[] campos, SocketAddress cliente) {
        if (!transferindo.add(cliente)) {
            return;
        }
        int id = proximoId++;
        new Thread(() -> receberUpload(id, campos, cliente)).start();
    }

    //abre socket, recebe a as partes, confere o hash, salva, avisa e confirma pro cliente
    private static void receberUpload(int id, String[] campos, SocketAddress cliente) {
        String nome = new File(campos[1]).getName();
        int total = Integer.parseInt(campos[3]);
        Protocolo.Avisos avisos = (operacao, detalhes) -> log(cliente, operacao, "AVISO", detalhes);

        try (DatagramSocket socketUpload = new DatagramSocket()) {
            log(cliente, "UPLOAD", "INICIO", nome + " (" + campos[2] + " bytes, " + total + " fragmentos)");
            byte[] arquivo = Protocolo.receberArquivo(socketUpload, cliente, total, "ACK|UPLOAD|" + id, avisos);

            if (!Protocolo.hash(arquivo).equals(campos[4])) {
                log(cliente, "UPLOAD", "ERRO", nome + " com hash diferente, descartado");
                Protocolo.confirmarUltimo(socketUpload, cliente, "ERROR|2|hash do arquivo nao confere");
                return;
            }
            File destino = new File(PASTA, nome);
            boolean existia = destino.exists();
            Files.write(destino.toPath(), arquivo);
            log(cliente, "UPLOAD", "OK", nome + " salvo (" + arquivo.length + " bytes)" + (existia ? ", sobrescrito" : ""));
            enviarListaParaTodos();
            Protocolo.confirmarUltimo(socketUpload, cliente, "ACK|" + id + "|" + (total - 1));
        } catch (IOException e) {
            log(cliente, "UPLOAD", "ERRO", nome + ": " + e.getMessage());
        } finally {
            transferindo.remove(cliente);
        }
    }

    //envia arquivo, igual o de receber, cria id, ignora repetidos, cria thread p enviar, tb confere c o arquivo existe
    private static void iniciarDownload(String nome, SocketAddress cliente) throws IOException {
        File arquivo = new File(PASTA, new File(nome).getName());
        if (!arquivo.isFile()) {
            Protocolo.enviar(socket, "ERROR|1|arquivo nao encontrado: " + nome, cliente);
            log(cliente, "DOWNLOAD", "ERRO", "arquivo inexistente: " + nome);
            return;
        }
        if (!transferindo.add(cliente)) {
            return;
        }
        int id = proximoId++;
        new Thread(() -> enviarDownload(id, arquivo, cliente)).start();
    }

    //pega o arquivo, divide em partes, manda as infos dess aerquivo e dps manda parte por parte
    private static void enviarDownload(int id, File arquivo, SocketAddress cliente) {
        Protocolo.Avisos avisos = (operacao, detalhes) -> log(cliente, operacao, "AVISO", detalhes);

        try (DatagramSocket socketDownload = new DatagramSocket()) {
            byte[] dados = Files.readAllBytes(arquivo.toPath());
            byte[][] partes = Protocolo.fragmentar(dados);
            log(cliente, "DOWNLOAD", "INICIO", arquivo.getName() + " (" + dados.length + " bytes, " + partes.length + " fragmentos)");

            String meta = "META|" + id + "|" + arquivo.getName() + "|" + dados.length + "|" + partes.length + "|" + Protocolo.hash(dados);
            Protocolo.enviarEsperar(socketDownload, meta.getBytes(StandardCharsets.UTF_8), cliente, "ACK|META", avisos);
            Protocolo.enviarArquivo(socketDownload, cliente, id, partes, avisos);
            log(cliente, "DOWNLOAD", "OK", arquivo.getName() + " enviado");
        } catch (IOException e) {
            log(cliente, "DOWNLOAD", "ERRO", arquivo.getName() + ": " + e.getMessage());
        } finally {
            transferindo.remove(cliente);
        }
    }

    //eh chamada a cada upload, manda a lista de arquivos para todos os clientes
    private static void enviarListaParaTodos() throws IOException {
        String mensagem = "UPDATE_LIST" + listaArquivos();
        for (SocketAddress cliente : clientes.values()) {
            Protocolo.enviar(socket, mensagem, cliente);
        }
        log(ENDERECO, "UPDATE_LIST", "OK", "lista enviada para " + clientes.size() + " cliente(s)");
    }

    //monta lista com todos os arquvios
    private static String listaArquivos() {
        String[] nomes = PASTA.list();
        Arrays.sort(nomes);
        StringBuilder lista = new StringBuilder();
        for (String nome : nomes) {
            lista.append("|").append(nome);
        }
        return lista.toString();
    }

    //registra e cria arquivo de logs
    public static synchronized void log(SocketAddress endereco, String operacao, String status, String detalhes) {
        InetSocketAddress e = (InetSocketAddress) endereco;
        String linha = "[" + LocalDateTime.now().format(FORMATO) + "] [" + e.getAddress().getHostAddress() + ":" + e.getPort()
                + "] [" + operacao + "] [" + status + "] " + detalhes;
        System.out.println(linha);
        try (FileWriter arquivo = new FileWriter("log_servidor.txt", true)) {
            arquivo.write(linha + System.lineSeparator());
        } catch (IOException ex) {
            System.out.println("erro ao gravar o log: " + ex.getMessage());
        }
    }
}
